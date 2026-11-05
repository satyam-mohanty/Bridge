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
    private var lastSignificantChange = Date.distantPast
    
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

            // Check for significant changes
            let statusChanged = path.status != self.lastPathStatus
            let interfacesChanged = interfaces != self.lastInterfaces

            if statusChanged || interfacesChanged {
                self.lastPathStatus = path.status
                self.lastInterfaces = interfaces

                self.debounceWorkItem?.cancel()
                let workItem = DispatchWorkItem { [weak self] in
                    self?.delegate?.networkPathDidChange()
                }
                self.debounceWorkItem = workItem

                // Use shorter debounce for status changes (Wi-Fi drop/connect), longer for interface shuffling
                let debounceMs = statusChanged ? 200 : 800
                self.queue.asyncAfter(deadline: .now() + .milliseconds(debounceMs), execute: workItem)
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
