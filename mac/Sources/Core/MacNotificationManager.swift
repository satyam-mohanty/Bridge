import Foundation
import UserNotifications
import AppKit

public final class MacNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = MacNotificationManager()
    
    private let center = UNUserNotificationCenter.current()
    private var isUNAuthorized: Bool = false
    private var activeRingingCalls = Set<String>()
    
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
        checkAuthorization()
    }
    
    public func checkAuthorization(completion: ((Bool) -> Void)? = nil) {
        center.getNotificationSettings { [weak self] settings in
            guard let self = self else { return }
            switch settings.authorizationStatus {
            case .authorized, .provisional:
                self.isUNAuthorized = true
                completion?(true)
            case .notDetermined:
                self.requestAuthorization(completion: completion)
            case .denied:
                self.isUNAuthorized = false
                LogManager.shared.log("UNUserNotificationCenter authorization is denied in System Settings for Bridge", level: .error)
                completion?(false)
            @unknown default:
                self.isUNAuthorized = false
                completion?(false)
            }
        }
    }
    
    public func requestAuthorization(completion: ((Bool) -> Void)? = nil) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            guard let self = self else { return }
            self.isUNAuthorized = granted
            if let error = error {
                LogManager.shared.log("Notification authorization failed: \(error)", level: .error)
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
            completionHandler([.banner, .sound, .list, .badge])
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }
    
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let pkg = userInfo["pkg"] as? String ?? ""
        let app = userInfo["app"] as? String ?? ""
        
        DispatchQueue.main.async {
            self.handleNotificationClick(pkg: pkg, appName: app)
        }
        completionHandler()
    }
    
    // MARK: - Notification Click Action
    public enum NotificationClickTarget: Equatable {
        case openWhatsAppApp
        case openWebURL(String)
        case activateBridge
    }
    
    public func hasWhatsAppDesktopApp() -> Bool {
        let workspace = NSWorkspace.shared
        let whatsappBundleIds = [
            "net.whatsapp.WhatsApp",
            "com.whatsapp.WhatsApp",
            "net.whatsapp.WhatsApp.desktop"
        ]
        for bundleId in whatsappBundleIds {
            if workspace.urlForApplication(withBundleIdentifier: bundleId) != nil {
                return true
            }
        }
        if let whatsappScheme = URL(string: "whatsapp://"),
           workspace.urlsForApplications(toOpen: whatsappScheme).first != nil {
            return true
        }
        return false
    }
    
    public func resolveNotificationClickTarget(pkg: String, appName: String, checkWhatsAppApp: Bool? = nil) -> NotificationClickTarget {
        let lowerPkg = pkg.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let lowerApp = appName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        
        // 1. WhatsApp: open native WhatsApp app if installed; otherwise open web WhatsApp
        if lowerPkg.contains("whatsapp") || lowerApp.contains("whatsapp") {
            let isAppAvailable = checkWhatsAppApp ?? hasWhatsAppDesktopApp()
            if isAppAvailable {
                return .openWhatsAppApp
            } else {
                return .openWebURL("https://web.whatsapp.com")
            }
        }
        
        // 2. Instagram: open instagram.com in browser
        if lowerPkg.contains("instagram") || lowerApp.contains("instagram") {
            return .openWebURL("https://www.instagram.com")
        }
        
        // 3. LinkedIn: open linkedin.com on the web
        if lowerPkg.contains("linkedin") || lowerApp.contains("linkedin") {
            return .openWebURL("https://www.linkedin.com")
        }
        
        // 4. Twitter / X: open x.com in default browser
        if lowerPkg.contains("twitter") || lowerPkg == "com.twitter.android" || lowerPkg == "com.twitter.android.lite" ||
           lowerApp == "x" || lowerApp == "twitter" || lowerApp.contains("twitter") {
            return .openWebURL("https://x.com")
        }
        
        // 5. Threads
        if lowerPkg.contains("barcelona") || lowerApp == "threads" || lowerApp.contains("threads") {
            return .openWebURL("https://www.threads.net")
        }
        
        // 6. Reddit
        if lowerPkg.contains("reddit") || lowerApp.contains("reddit") {
            return .openWebURL("https://www.reddit.com")
        }
        
        // 7. YouTube
        if lowerPkg.contains("youtube") || lowerApp.contains("youtube") {
            if lowerPkg.contains("music") || lowerApp.contains("music") {
                return .openWebURL("https://music.youtube.com")
            } else {
                return .openWebURL("https://www.youtube.com")
            }
        }
        
        // 8. Facebook
        if lowerPkg.contains("facebook.katana") || lowerPkg.contains("facebook.lite") || lowerApp == "facebook" {
            return .openWebURL("https://www.facebook.com")
        }
        
        // 9. Telegram
        if lowerPkg.contains("telegram") || lowerApp.contains("telegram") {
            return .openWebURL("https://web.telegram.org")
        }
        
        // 10. Messenger
        if lowerPkg.contains("orca") || lowerPkg.contains("facebook.mlite") || (lowerApp.contains("messenger") && !lowerPkg.contains("telegram")) {
            return .openWebURL("https://www.messenger.com")
        }
        
        // 11. Discord
        if lowerPkg.contains("discord") || lowerApp.contains("discord") {
            return .openWebURL("https://discord.com/app")
        }
        
        // 12. Slack
        if lowerPkg.contains("slack") || lowerApp.contains("slack") {
            return .openWebURL("https://slack.com")
        }
        
        // 13. GitHub
        if lowerPkg.contains("github") || lowerApp.contains("github") {
            return .openWebURL("https://github.com")
        }
        
        // 14. Gmail
        if lowerPkg == "com.google.android.gm" || lowerApp.contains("gmail") {
            return .openWebURL("https://mail.google.com")
        }
        
        // 15. Google Calendar
        if lowerPkg.contains("calendar") || lowerApp.contains("calendar") {
            return .openWebURL("https://calendar.google.com")
        }
        
        // 16. Google Drive / Docs / Sheets
        if lowerPkg.contains("docs") || lowerPkg.contains("drive") || lowerApp.contains("drive") {
            return .openWebURL("https://drive.google.com")
        }
        
        // 17. Notion
        if lowerPkg.contains("notion") || lowerApp.contains("notion") {
            return .openWebURL("https://www.notion.so")
        }
        
        // 18. Spotify
        if lowerPkg.contains("spotify") || lowerApp.contains("spotify") {
            return .openWebURL("https://open.spotify.com")
        }
        
        // 19. Pinterest
        if lowerPkg.contains("pinterest") || lowerApp.contains("pinterest") {
            return .openWebURL("https://www.pinterest.com")
        }
        
        // 20. TikTok
        if lowerPkg.contains("musically") || lowerPkg.contains("trill") || lowerApp.contains("tiktok") {
            return .openWebURL("https://www.tiktok.com")
        }
        
        // 21. Snapchat
        if lowerPkg.contains("snapchat") || lowerApp.contains("snapchat") {
            return .openWebURL("https://web.snapchat.com")
        }
        
        // 22. Amazon
        if lowerPkg.contains("amazon") || lowerApp.contains("amazon") {
            return .openWebURL("https://www.amazon.com")
        }
        
        // 23. Netflix
        if lowerPkg.contains("netflix") || lowerApp.contains("netflix") {
            return .openWebURL("https://www.netflix.com")
        }
        
        // 24. Twitch
        if lowerPkg.contains("twitch") || lowerApp.contains("twitch") {
            return .openWebURL("https://www.twitch.tv")
        }
        
        // 25. Quora
        if lowerPkg.contains("quora") || lowerApp.contains("quora") {
            return .openWebURL("https://www.quora.com")
        }
        
        // 26. Medium
        if lowerPkg.contains("medium") || lowerApp.contains("medium") {
            return .openWebURL("https://medium.com")
        }
        
        // 27. Substack
        if lowerPkg.contains("substack") || lowerApp.contains("substack") {
            return .openWebURL("https://substack.com")
        }
        
        // 28. Outlook
        if lowerPkg.contains("outlook") || lowerApp.contains("outlook") {
            return .openWebURL("https://outlook.live.com")
        }
        
        // 29. Teams
        if lowerPkg.contains("teams") || lowerApp.contains("teams") {
            return .openWebURL("https://teams.microsoft.com")
        }
        
        // 30. Trello
        if lowerPkg.contains("trello") || lowerApp.contains("trello") {
            return .openWebURL("https://trello.com")
        }
        
        // 31. Zoom
        if lowerPkg.contains("zoom") || lowerApp.contains("zoom") {
            return .openWebURL("https://zoom.us")
        }
        
        // 32. Heuristic for other web platforms:
        // If package has a domain pattern (e.g. com.airbnb.android) and is not an Android system service
        if !lowerPkg.isEmpty && !lowerPkg.hasPrefix("com.android.") && !lowerPkg.hasPrefix("com.google.android.googlequicksearchbox") && !lowerPkg.hasPrefix("com.bridge.") {
            let parts = lowerPkg.split(separator: ".")
            if parts.count >= 2 {
                let domain = String(parts[1])
                let tld = String(parts[0])
                if (tld == "com" || tld == "org" || tld == "io" || tld == "net" || tld == "app" || tld == "co") && domain.count > 2 {
                    let candidate = "https://www.\(domain).\(tld)"
                    if let url = URL(string: candidate) {
                        return .openWebURL(url.absoluteString)
                    }
                }
            }
        }
        
        // Fallback: bring Bridge window to front
        return .activateBridge
    }
    
    public func handleNotificationClick(pkg: String, appName: String) {
        LogManager.shared.log("Notification clicked: pkg=\(pkg), app=\(appName)")
        let target = resolveNotificationClickTarget(pkg: pkg, appName: appName)
        switch target {
        case .openWhatsAppApp:
            openWhatsApp()
        case .openWebURL(let urlString):
            openWebURL(urlString)
        case .activateBridge:
            activateBridgeApp()
        }
    }
    
    public func openWhatsApp() {
        let workspace = NSWorkspace.shared
        
        // 1. Check known bundle IDs for WhatsApp desktop app on macOS
        let whatsappBundleIds = [
            "net.whatsapp.WhatsApp",
            "com.whatsapp.WhatsApp",
            "net.whatsapp.WhatsApp.desktop"
        ]
        
        for bundleId in whatsappBundleIds {
            if let appUrl = workspace.urlForApplication(withBundleIdentifier: bundleId) {
                LogManager.shared.log("Found native WhatsApp desktop app at: \(appUrl.path)")
                let config = NSWorkspace.OpenConfiguration()
                config.activates = true
                workspace.openApplication(at: appUrl, configuration: config) { app, error in
                    if let error = error {
                        LogManager.shared.log("Error launching WhatsApp desktop app: \(error.localizedDescription)", level: .error)
                        self.openWebURL("https://web.whatsapp.com")
                    } else {
                        LogManager.shared.log("WhatsApp desktop app launched successfully")
                    }
                }
                return
            }
        }
        
        // 2. Check if any app is registered to handle whatsapp:// scheme
        if let whatsappScheme = URL(string: "whatsapp://"),
           let appUrl = workspace.urlsForApplications(toOpen: whatsappScheme).first {
            LogManager.shared.log("Found application handling whatsapp:// at: \(appUrl.path)")
            let config = NSWorkspace.OpenConfiguration()
            config.activates = true
            workspace.openApplication(at: appUrl, configuration: config) { app, error in
                if let error = error {
                    LogManager.shared.log("Error opening whatsapp:// app: \(error.localizedDescription)", level: .error)
                    self.openWebURL("https://web.whatsapp.com")
                } else {
                    LogManager.shared.log("WhatsApp scheme opened successfully")
                }
            }
            return
        }
        
        // 3. Fallback: WhatsApp desktop app is not available on Mac -> open web WhatsApp
        LogManager.shared.log("WhatsApp desktop app is not available on this Mac. Opening web.whatsapp.com...")
        openWebURL("https://web.whatsapp.com")
    }
    
    public func openWebURL(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        LogManager.shared.log("Opening web application in default browser: \(urlString)")
        NSWorkspace.shared.open(url)
    }
    
    public func activateBridgeApp() {
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            for window in NSApp.windows where window.canBecomeMain {
                window.makeKeyAndOrderFront(nil)
                break
            }
        }
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
        
        postNotification(
            key: msg.key,
            pkg: msg.pkg,
            app: msg.app,
            title: title,
            subtitle: subtitle,
            body: body,
            contactPhotoData: contactPhotoData,
            iconFileUrl: hasAppIcon ? iconFileUrl : nil
        )
    }
    
    private func postNotification(
        key: String,
        pkg: String,
        app: String,
        title: String,
        subtitle: String,
        body: String,
        contactPhotoData: Data?,
        iconFileUrl: URL?
    ) {
        center.getNotificationSettings { [weak self] settings in
            guard let self = self else { return }
            switch settings.authorizationStatus {
            case .authorized, .provisional:
                self.deliverViaUN(
                    key: key,
                    pkg: pkg,
                    app: app,
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: contactPhotoData,
                    iconFileUrl: iconFileUrl
                )
            case .notDetermined:
                self.requestAuthorization { granted in
                    if granted {
                        self.deliverViaUN(
                            key: key,
                            pkg: pkg,
                            app: app,
                            title: title,
                            subtitle: subtitle,
                            body: body,
                            contactPhotoData: contactPhotoData,
                            iconFileUrl: iconFileUrl
                        )
                    } else {
                        LogManager.shared.log("Notification authorization denied by user", level: .error)
                    }
                }
            case .denied:
                LogManager.shared.log("Notification not allowed in macOS System Settings for Bridge", level: .error)
            @unknown default:
                self.deliverViaUN(
                    key: key,
                    pkg: pkg,
                    app: app,
                    title: title,
                    subtitle: subtitle,
                    body: body,
                    contactPhotoData: contactPhotoData,
                    iconFileUrl: iconFileUrl
                )
            }
        }
    }
    
    private func deliverViaUN(
        key: String,
        pkg: String,
        app: String,
        title: String,
        subtitle: String,
        body: String,
        contactPhotoData: Data?,
        iconFileUrl: URL?
    ) {
        let content = UNMutableNotificationContent()
        content.title = title
        if !subtitle.isEmpty {
            content.subtitle = subtitle
        }
        content.body = body
        content.sound = .default
        content.userInfo = [
            "pkg": pkg,
            "app": app,
            "key": key
        ]
        
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
        center.add(request) { error in
            if let error = error {
                LogManager.shared.log("Failed to schedule UNNotification: \(error)", level: .error)
            } else {
                LogManager.shared.log("Notification displayed: [\(app)] \(title) - \(body)")
            }
        }
    }
    
    // MARK: - Removal
    public func removeNotification(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
        center.removePendingNotificationRequests(withIdentifiers: [key])
    }

    public func handleNotificationRemove(_ msg: NotifRemoveMessage) {
        removeNotification(key: msg.key)
        LogManager.shared.log("Notification removed: key=\(msg.key)")
    }
    
    // MARK: - Call Event Handling (Stage B)
    public func handleCallEvent(_ msg: CallEventMessage, showNotifications: Bool) {
        guard showNotifications else {
            LogManager.shared.log("Call event filtered: id=\(msg.id) state=\(msg.state) reason=disabled_on_mac")
            return
        }
        
        let callKey = "bridge-call-\(msg.id)"
        let callerLabel = msg.name ?? msg.number ?? "Unknown caller"
        
        switch msg.state {
        case "ringing":
            activeRingingCalls.insert(msg.id)
            let bodyText = (msg.name != nil && msg.number != nil) ? msg.number! : "Incoming phone call"
            
            postNotification(
                key: callKey,
                pkg: "com.android.server.telecom",
                app: "Phone",
                title: "Incoming call",
                subtitle: callerLabel,
                body: bodyText,
                contactPhotoData: nil,
                iconFileUrl: nil
            )
            LogManager.shared.log("Notification displayed: [Call] Incoming call - \(callerLabel)")
            
        case "answered":
            activeRingingCalls.remove(msg.id)
            removeNotification(key: callKey)
            LogManager.shared.log("Incoming call answered: id=\(msg.id)")
            
        case "ended":
            let wasRinging = activeRingingCalls.remove(msg.id) != nil
            removeNotification(key: callKey)
            
            if wasRinging {
                // Ended without being answered -> MISSED CALL!
                let missedKey = "bridge-missed-call-\(msg.id)-\(msg.ts)"
                let missedTitle = "Missed call from \(callerLabel)"
                let missedBody = (msg.name != nil && msg.number != nil) ? "Missed call from \(callerLabel) (\(msg.number!))" : "Missed call from \(callerLabel)"
                
                postNotification(
                    key: missedKey,
                    pkg: "com.android.server.telecom",
                    app: "Phone",
                    title: missedTitle,
                    subtitle: "Phone",
                    body: missedBody,
                    contactPhotoData: nil,
                    iconFileUrl: nil
                )
                LogManager.shared.log("Notification displayed: [Call] Missed call from \(callerLabel)")
            } else {
                LogManager.shared.log("Call ended after answer: id=\(msg.id)")
            }
            
        case "missed":
            activeRingingCalls.remove(msg.id)
            removeNotification(key: callKey)
            
            let missedKey = "bridge-missed-call-\(msg.id)-\(msg.ts)"
            let missedTitle = "Missed call from \(callerLabel)"
            let missedBody = (msg.name != nil && msg.number != nil) ? "Missed call from \(callerLabel) (\(msg.number!))" : "Missed call from \(callerLabel)"
            
            postNotification(
                key: missedKey,
                pkg: "com.android.server.telecom",
                app: "Phone",
                title: missedTitle,
                subtitle: "Phone",
                body: missedBody,
                contactPhotoData: nil,
                iconFileUrl: nil
            )
            LogManager.shared.log("Notification displayed: [Call] Missed call from \(callerLabel)")
            
        default:
            LogManager.shared.log("Unknown call state: \(msg.state)", level: .error)
        }
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
        
        postNotification(
            key: key,
            pkg: "com.bridge.android",
            app: "Bridge",
            title: title,
            subtitle: subtitle,
            body: body,
            contactPhotoData: nil,
            iconFileUrl: nil
        )
    }
}
