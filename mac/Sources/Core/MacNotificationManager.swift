import Foundation
import UserNotifications
import AppKit

public final class MacNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = MacNotificationManager()
    
    private let center = UNUserNotificationCenter.current()
    
    private var iconCacheDir: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("Bridge/IconCache", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }
    
    private override init() {
        super.init()
        center.delegate = self
        requestAuthorization()
    }
    
    public func requestAuthorization(completion: ((Bool) -> Void)? = nil) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                LogManager.shared.log("Notification authorization failed: \(error)", level: .error)
            } else {
                LogManager.shared.log("Notification authorization granted: \(granted)")
            }
            completion?(granted)
        }
    }
    
    // Ensure notifications display even when the app is focused
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if #available(macOS 14.0, *) {
            completionHandler([.banner, .sound, .list])
        } else {
            completionHandler([.alert, .sound])
        }
    }
    
    public func handleNotificationPost(_ msg: NotifPostMessage, showNotifications: Bool, showPreviews: Bool) {
        guard showNotifications else {
            LogManager.shared.log("Notification filtered: pkg=\(msg.pkg) reason=disabled_on_mac")
            return
        }
        
        let content = UNMutableNotificationContent()
        
        if showPreviews {
            if let sender = msg.sender, !sender.trimmingCharacters(in: .whitespaces).isEmpty {
                content.title = sender
                content.subtitle = msg.app
            } else if !msg.title.trimmingCharacters(in: .whitespaces).isEmpty {
                content.title = msg.title
                content.subtitle = msg.app
            } else {
                content.title = msg.app
            }
            
            let displayBody = (msg.bigText?.isEmpty == false) ? msg.bigText! : msg.text
            content.body = displayBody
        } else {
            content.title = msg.app
            content.subtitle = ""
            content.body = "New message"
        }
        
        content.sound = .default
        
        // Attachment: contact photo or app icon
        var attachmentSet = false
        
        // 1. Contact photo (largeIcon)
        if showPreviews, let largeIconBase64 = msg.largeIcon, let iconData = Data(base64Encoded: largeIconBase64) {
            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("contact_\(UUID().uuidString).jpg")
            do {
                try iconData.write(to: tempUrl)
                let attachment = try UNNotificationAttachment(identifier: "contactPhoto", url: tempUrl, options: nil)
                content.attachments = [attachment]
                attachmentSet = true
            } catch {
                LogManager.shared.log("Failed to attach contact photo: \(error)", level: .error)
            }
        }
        
        // 2. App icon from cache
        if !attachmentSet {
            let iconFileUrl = iconCacheDir.appendingPathComponent("\(msg.pkg)_\(msg.versionCode).png")
            if FileManager.default.fileExists(atPath: iconFileUrl.path) {
                do {
                    let attachment = try UNNotificationAttachment(identifier: "appIcon", url: iconFileUrl, options: nil)
                    content.attachments = [attachment]
                    attachmentSet = true
                } catch {
                    LogManager.shared.log("Failed to attach cached app icon: \(error)", level: .error)
                }
            } else {
                // Request icon from Android
                LogManager.shared.log("Icon cache miss for \(msg.pkg) (v\(msg.versionCode)), requesting from Android")
                NetworkServer.shared.send(type: .iconNeed, value: IconNeedMessage(pkg: msg.pkg))
            }
        }
        
        let request = UNNotificationRequest(identifier: msg.key, content: content, trigger: nil)
        center.add(request) { error in
            if let error = error {
                LogManager.shared.log("Failed to schedule notification: \(error)", level: .error)
            } else {
                LogManager.shared.log("Notification displayed: [\(msg.app)] \(content.title) - \(content.body)")
            }
        }
    }
    
    public func handleNotificationRemove(_ msg: NotifRemoveMessage) {
        center.removeDeliveredNotifications(withIdentifiers: [msg.key])
        center.removePendingNotificationRequests(withIdentifiers: [msg.key])
        LogManager.shared.log("Notification removed: key=\(msg.key)")
    }
    
    public func handleIconResponse(_ msg: IconRespMessage) {
        guard let data = Data(base64Encoded: msg.icon) else {
            LogManager.shared.log("Invalid base64 in icon response for \(msg.pkg)", level: .error)
            return
        }
        
        let fileUrl = iconCacheDir.appendingPathComponent("\(msg.pkg)_\(msg.versionCode).png")
        do {
            try data.write(to: fileUrl)
            LogManager.shared.log("Saved app icon cache for \(msg.pkg) (version \(msg.versionCode))")
        } catch {
            LogManager.shared.log("Failed to save icon cache for \(msg.pkg): \(error)", level: .error)
        }
    }
    
    public func sendTestNotification() {
        requestAuthorization { [weak self] granted in
            guard let self = self, granted else { return }
            
            let content = UNMutableNotificationContent()
            content.title = "Bridge"
            content.subtitle = "Android Sync"
            content.body = "Notifications are connected and working properly."
            content.sound = .default
            
            let request = UNNotificationRequest(identifier: "bridge-test-notification-\(UUID().uuidString)", content: content, trigger: nil)
            self.center.add(request) { error in
                if let error = error {
                    LogManager.shared.log("Test notification error: \(error)", level: .error)
                } else {
                    LogManager.shared.log("Notification displayed: [Bridge] Test Notification")
                }
            }
        }
    }
}
