import Foundation
import Network

public final class DiscoveryBeacon {
    private let port: NWEndpoint.Port = 48250
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "com.bridge.mac.beacon", qos: .utility)
    private var isRunning = false
    
    public init() {}
    
    public func start() {
        queue.async { [weak self] in
            guard let self = self, !self.isRunning else { return }
            self.isRunning = true
            LogManager.shared.log("Starting UDP discovery beacon on port 48250 (3s interval)")
            
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now(), repeating: .seconds(3), leeway: .milliseconds(200))
            timer.setEventHandler { [weak self] in
                self?.sendBeaconPacket()
            }
            timer.resume()
            self.timer = timer
        }
    }
    
    public func stop() {
        queue.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.isRunning = false
            self.timer?.cancel()
            self.timer = nil
            LogManager.shared.log("Stopped UDP discovery beacon")
        }
    }
    
    private func sendBeaconPacket() {
        guard isRunning else { return }
        
        let machineName = Host.current().localizedName ?? "Mac"
        let beacon = BeaconInfo(v: 1, name: machineName, port: Int(port.rawValue))
        guard let payload = try? JSONEncoder().encode(beacon) else { return }
        
        // Broadcast over UDP 255.255.255.255:48250
        let host = NWEndpoint.Host("255.255.255.255")
        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true
        
        let connection = NWConnection(host: host, port: port, using: params)
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                connection.send(content: payload, completion: .contentProcessed({ _ in
                    connection.cancel()
                }))
            } else if case .failed = state {
                connection.cancel()
            }
        }
        connection.start(queue: queue)
    }
}
