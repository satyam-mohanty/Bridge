import Foundation
import IOKit.ps

public struct MacBatteryInfo: Equatable {
    public let level: Int
    public let isCharging: Bool
}

public enum MacBatteryMonitor {
    public static func getCurrentBatteryInfo() -> MacBatteryInfo? {
        guard let snapshot = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let sources = IOPSCopyPowerSourcesList(snapshot)?.takeRetainedValue() as? [CFTypeRef] else {
            return nil
        }
        
        for source in sources {
            guard let desc = IOPSGetPowerSourceDescription(snapshot, source)?.takeUnretainedValue() as? [String: Any] else {
                continue
            }
            
            if let type = desc[kIOPSTypeKey] as? String, type == kIOPSInternalBatteryType {
                let current = desc[kIOPSCurrentCapacityKey] as? Int ?? 0
                let max = desc[kIOPSMaxCapacityKey] as? Int ?? 100
                let isCharging = desc[kIOPSIsChargingKey] as? Bool ?? false
                let powerState = desc[kIOPSPowerSourceStateKey] as? String
                let level = max > 0 ? Int((Double(current) / Double(max)) * 100.0) : current
                let charging = isCharging || (powerState == "AC Power" && current < max)
                return MacBatteryInfo(level: Swift.max(0, Swift.min(100, level)), isCharging: charging)
            }
        }
        
        // If desktop Mac (no internal battery), check if on AC Power
        for source in sources {
            if let desc = IOPSGetPowerSourceDescription(snapshot, source)?.takeUnretainedValue() as? [String: Any] {
                let current = desc[kIOPSCurrentCapacityKey] as? Int ?? 100
                let max = desc[kIOPSMaxCapacityKey] as? Int ?? 100
                let level = max > 0 ? Int((Double(current) / Double(max)) * 100.0) : 100
                return MacBatteryInfo(level: Swift.max(0, Swift.min(100, level)), isCharging: true)
            }
        }
        
        return nil
    }
}
