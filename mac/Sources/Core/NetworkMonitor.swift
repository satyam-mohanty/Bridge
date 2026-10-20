import Foundation
import Network
import AppKit

public protocol NetworkMonitorDelegate: AnyObject {
    func networkPathDidChange()
    func systemWillSleep()
    func systemDidWake()
}

public final class NetworkMonitor {
    public weak var delegate: NetworkMonitorDelegate?
    
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.bridge.mac.netmonitor")
    private var lastInterfaceCount: Int = 0
    private var hasStarted = false
    
    private var debounceWorkItem: DispatchWorkItem?
    private var isFirstPathUpdate = true
    private var lastPathStatus: NWPath.Status?
    private var lastInterfaces: [String] = []
    
    public init() {
        setupSleepWakeObservers()
    }
    
    public func start() {
        guard !hasStarted else { return }
        hasStarted = true
        
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let interfaces = path.availableInterfaces.map { $0.name }.sorted()
            LogManager.shared.log("Network path update: status=\(path.status), interfaces=\(interfaces)")
            
            // On startup, record initial state without triggering unnecessary restarts
            if self.isFirstPathUpdate {
                self.isFirstPathUpdate = false
                self.lastPathStatus = path.status
                self.lastInterfaces = interfaces
                return
            }
            
            // Only trigger if network status or active interfaces actually changed
            if path.status != self.lastPathStatus || interfaces != self.lastInterfaces {
                self.lastPathStatus = path.status
                self.lastInterfaces = interfaces
                
                self.debounceWorkItem?.cancel()
                let workItem = DispatchWorkItem { [weak self] in
                    self?.delegate?.networkPathDidChange()
                }
                self.debounceWorkItem = workItem
                // 800ms debounce allows kernel socket transitions to settle
                self.queue.asyncAfter(deadline: .now() + .milliseconds(800), execute: workItem)
            }
        }
        monitor.start(queue: queue)
    }
    
    public func stop() {
        monitor.cancel()
    }
    
    private func setupSleepWakeObservers() {
        let center = NSWorkspace.shared.notificationCenter
        center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            LogManager.shared.log("Mac going to sleep: suspending Bridge network activities")
            self?.delegate?.systemWillSleep()
        }
        center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            LogManager.shared.log("Mac woke up: instantly restarting Bridge listeners and beacons")
            self?.delegate?.systemDidWake()
        }
    }
}
