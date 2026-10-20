import Foundation
import os

public final class LogManager: @unchecked Sendable {
    public static let shared = LogManager()
    
    private let logger = Logger(subsystem: "com.bridge.mac", category: "Bridge")
    private let lock = NSLock()
    private var logs: [String] = []
    private let maxLogs = 200
    
    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return f
    }()
    
    private init() {}
    
    public func log(_ message: String, level: OSLogType = .default) {
        let timestamp = dateFormatter.string(from: Date())
        let formatted = "[\(timestamp)] \(message)"
        
        lock.lock()
        logs.append(formatted)
        if logs.count > maxLogs {
            logs.removeFirst(logs.count - maxLogs)
        }
        lock.unlock()
        
        switch level {
        case .info:
            logger.info("\(message, privacy: .public)")
        case .error:
            logger.error("\(message, privacy: .public)")
        case .fault:
            logger.fault("\(message, privacy: .public)")
        default:
            logger.log("\(message, privacy: .public)")
        }
    }
    
    public func getDiagnostics() -> String {
        lock.lock()
        defer { lock.unlock() }
        if logs.isEmpty {
            return "No logs recorded yet."
        }
        return logs.joined(separator: "\n")
    }
}
