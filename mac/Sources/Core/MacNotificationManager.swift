import Foundation
import UserNotifications
import AppKit

public final class MacNotificationManager: NSObject, UNUserNotificationCenterDelegate, NSUserNotificationCenterDelegate {
    public static let shared = MacNotificationManager()
    
    private let center = UNUserNotificationCenter.current()
    private var isUNAuthorized: Bool = false
    
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
        NSUserNotificationCenter.default.delegate = self
        checkAuthorization()
    }
    
    public func checkAuthorization(completion: ((Bool) -> Void)? = nil) {
        center.getNotificationSettings { [weak self] settings in
            guard let self = self else { return }
            if settings.authorizationStatus == .authorized {
                self.isUNAuthorized = true
                completion?(true)
            } else if settings.authorizationStatus == .notDetermined {
                self.requestAuthorization(completion: completion)
            } else {
                self.isUNAuthorized = false
                LogManager.shared.log("UNUserNotificationCenter authorization status: \(settings.authorizationStatus.rawValue); direct delivery active")
                completion?(false)
            }
        }
    }
    
    public func requestAuthorization(completion: ((Bool) -> Void)? = nil) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            guard let self = self else { return }
            self.isUNAuthorized = granted
            if let error = error {
                LogManager.shared.log("Notification authorization failed: \(error); using direct delivery fallback", level: .error)
            } else {
                LogManager.shared.log("Notification authorization granted: \(granted)")
            }
            completion?(granted)
        }
    }
    
    public func openSystemNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }
    
    // MARK: - UNUserNotificationCenterDelegate
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
    
    // MARK: - NSUserNotificationCenterDelegate
    public func userNotificationCenter(_ center: NSUserNotificationCenter, shouldPresent notification: NSUserNotification) -> Bool {
        return true
    }
    
    // MARK: - Notification Post Handling
    public func handleNotificationPost(_ msg: NotifPostMessage, showNotifications: Bool, showPreviews: Bool) {
        guard showNotifications else {
            LogManager.shared.log("Notification filtered: pkg=\(msg.pkg) reason=disabled_on_mac")
            return
        }
        
        let title: String
        let subtitle: String
        let body: String
        
        if showPreviews {
            if let sender = msg.sender, !sender.trimmingCharacters(in: .whitespaces).isEmpty {
                title = sender
                subtitle = msg.app
            } else if !msg.title.trimmingCharacters(in: .whitespaces).isEmpty {
                title = msg.title
                subtitle = msg.app
            } else {
                title = msg.app
                subtitle = ""
            }
            body = (msg.bigText?.isEmpty == false) ? msg.bigText! : msg.text
        } else {
            title = msg.app
            subtitle = ""
            body = "New message"
        }
        
        // Prepare image data for attachment / contentImage
        var contactPhotoData: Data? = nil
        if showPreviews, let largeIconBase64 = msg.largeIcon, let iconData = Data(base64Encoded: largeIconBase64) {
            contactPhotoData = iconData
        }
        
        let iconFileUrl = iconCacheDir.appendingPathComponent("\(msg.pkg)_\(msg.versionCode).png")
        let hasAppIcon = FileManager.default.fileExists(atPath: iconFileUrl.path)
        if !hasAppIcon && contactPhotoData == nil {
            LogManager.shared.log("Icon cache miss for \(msg.pkg) (v\(msg.versionCode)), requesting from Android")
            NetworkServer.shared.send(type: .iconNeed, value: IconNeedMessage(pkg: msg.pkg))
        }
        
        center.getNotificationSettings { [weak self] settings in
            guard let self = self else { return }
            if settings.authorizationStatus == .authorized {
                self.deliverViaUN(
                    key: msg.key,
                    app: msg.app,
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: contactPhotoData,
                    iconFileUrl: hasAppIcon ? iconFileUrl : nil
                )
            } else {
                self.deliverViaNS(
                    key: msg.key,
                    app: msg.app,
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: contactPhotoData,
                    iconFileUrl: hasAppIcon ? iconFileUrl : nil
                )
            }
        }
    }
    
    private func deliverViaUN(
        key: String,
        app: String,
        title: String,
        subtitle: String,
        body: String,
        contactPhotoData: Data?,
        iconFileUrl: URL?
    ) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.subtitle = subtitle
        content.body = body
        content.sound = .default
        
        var attachmentSet = false
        if let photoData = contactPhotoData {
            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("contact_\(UUID().uuidString).jpg")
            do {
                try photoData.write(to: tempUrl)
                let attachment = try UNNotificationAttachment(identifier: "contactPhoto", url: tempUrl, options: nil)
                content.attachments = [attachment]
                attachmentSet = true
            } catch {
                LogManager.shared.log("Failed to attach contact photo: \(error)", level: .error)
            }
        }
        
        if !attachmentSet, let iconUrl = iconFileUrl {
            do {
                let attachment = try UNNotificationAttachment(identifier: "appIcon", url: iconUrl, options: nil)
                content.attachments = [attachment]
            } catch {
                LogManager.shared.log("Failed to attach cached app icon: \(error)", level: .error)
            }
        }
        
        let request = UNNotificationRequest(identifier: key, content: content, trigger: nil)
        center.add(request) { [weak self] error in
            if let error = error {
                LogManager.shared.log("Failed to schedule UNNotification: \(error), falling back to direct delivery", level: .error)
                self?.deliverViaNS(
                    key: key,
                    app: app,
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: contactPhotoData,
                    iconFileUrl: iconFileUrl
                )
            } else {
                LogManager.shared.log("Notification displayed: [\(app)] \(title) - \(body)")
            }
        }
    }
    
    private func deliverViaNS(
        key: String,
        app: String,
        title: String,
        subtitle: String,
        body: String,
        contactPhotoData: Data?,
        iconFileUrl: URL?
    ) {
        DispatchQueue.main.async {
            let notif = NSUserNotification()
            notif.identifier = key
            notif.title = title
            notif.subtitle = subtitle
            notif.informativeText = body
            notif.soundName = NSUserNotificationDefaultSoundName
            
            if let photoData = contactPhotoData {
                notif.contentImage = NSImage(data: photoData)
            } else if let iconUrl = iconFileUrl {
                notif.contentImage = NSImage(contentsOf: iconUrl)
            }
            
            NSUserNotificationCenter.default.deliver(notif)
            LogManager.shared.log("Notification displayed: [\(app)] \(title) - \(body)")
        }
    }
    
    // MARK: - Removal
    public func handleNotificationRemove(_ msg: NotifRemoveMessage) {
        center.removeDeliveredNotifications(withIdentifiers: [msg.key])
        center.removePendingNotificationRequests(withIdentifiers: [msg.key])
        
        DispatchQueue.main.async {
            for delivered in NSUserNotificationCenter.default.deliveredNotifications {
                if delivered.identifier == msg.key {
                    NSUserNotificationCenter.default.removeDeliveredNotification(delivered)
                }
            }
        }
        LogManager.shared.log("Notification removed: key=\(msg.key)")
    }
    
    // MARK: - Icon Cache
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
    
    // MARK: - Test Notification
    public func sendTestNotification() {
        LogManager.shared.log("Sending test notification")
        let key = "bridge-test-notification-\(UUID().uuidString)"
        let title = "Bridge"
        let subtitle = "Android Sync"
        let body = "Notifications are connected and working properly."
        
        center.getNotificationSettings { [weak self] settings in
            guard let self = self else { return }
            if settings.authorizationStatus == .authorized {
                self.deliverViaUN(
                    key: key,
                    app: "Bridge",
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: nil,
                    iconFileUrl: nil
                )
            } else {
                self.deliverViaNS(
                    key: key,
                    app: "Bridge",
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: nil,
                    iconFileUrl: nil
                )
            }
        }
    }
}
