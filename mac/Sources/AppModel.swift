import Foundation
import SwiftUI
import ServiceManagement
import AppKit
import Darwin

public struct FileTransferItem: Identifiable, Equatable {
    public let id: String
    public let name: String
    public var size: Int
    public var url: URL?
    public let isIncoming: Bool
    public var progress: Double
    public var status: String
    public let date: Date
    
    public init(id: String = UUID().uuidString, name: String, size: Int, url: URL? = nil, isIncoming: Bool, progress: Double = 1.0, status: String, date: Date = Date()) {
        self.id = id
        self.name = name
        self.size = size
        self.url = url
        self.isIncoming = isIncoming
        self.progress = progress
        self.status = status
        self.date = date
    }
}

public struct NotificationHistoryItem: Identifiable, Equatable {
    public let id: String
    public let app: String
    public let title: String
    public let body: String
    public let date: Date
    public let pkg: String
    public var isUnread: Bool
    
    public init(id: String = UUID().uuidString, app: String, title: String, body: String, date: Date = Date(), pkg: String = "", isUnread: Bool = true) {
        self.id = id
        self.app = app
        self.title = title
        self.body = body
        self.date = date
        self.pkg = pkg
        self.isUnread = isUnread
    }
}

public struct ClipboardSnippetItem: Identifiable, Equatable {
    public let id: String
    public let text: String
    public let origin: String // "Pixel → Mac" or "Mac → Pixel"
    public let date: Date
    public let isLink: Bool
    public var isCurrent: Bool
    
    public init(id: String = UUID().uuidString, text: String, origin: String, date: Date = Date(), isLink: Bool = false, isCurrent: Bool = false) {
        self.id = id
        self.text = text
        self.origin = origin
        self.date = date
        self.isLink = isLink
        self.isCurrent = isCurrent
    }
}

@MainActor
public final class AppModel: ObservableObject, NetworkServerDelegate, NetworkMonitorDelegate {
    @Published public
    var isConnected: Bool = false
    @Published public var clientDescription: String? = nil
    @Published public var pairingJson: String = ""
    @Published public var isOpenAtLogin: Bool = false
    @Published public var hideFromDock: Bool {
        didSet {
            UserDefaults.standard.set(hideFromDock, forKey: "hideFromDock")
            NSApp.setActivationPolicy(hideFromDock ? .accessory : .regular)
        }
    }
    @Published public var downloadsDirectoryPath: String = ""
    @Published public var recentTransfers: [FileTransferItem] = []
    @Published public var recentNotifications: [NotificationHistoryItem] = []
    @Published public var isTransferring: Bool = false
    @Published public var activeTransferProgress: Double = 0.0
    @Published public var activeTransferName: String = ""
    @Published public var showAndroidNotifications: Bool {
        didSet {
            UserDefaults.standard.set(showAndroidNotifications, forKey: "showAndroidNotifications")
        }
    }
    @Published public var showNotificationPreviews: Bool {
        didSet {
            UserDefaults.standard.set(showNotificationPreviews, forKey: "showNotificationPreviews")
        }
    }
    @Published public var peerBatteryLevel: Int? = nil
    @Published public var isPeerCharging: Bool = false
    @Published public var recentClipboardSnippets: [ClipboardSnippetItem] = []
    @Published public var connectedDeviceName: String = UserDefaults.standard.string(forKey: "connectedDeviceName") ?? ""
    @Published public var hasPairedDevice: Bool = (UserDefaults.standard.string(forKey: "connectedDeviceName") != nil)
    @Published public var networkName: String = "Local Wi-Fi"
    @Published public var deviceIpAddress: String = "—"
    @Published public var pairingCode: String = "482 916"
    @Published public var isClipboardSyncEnabled: Bool = true
    @Published public var respectFocusOnMac: Bool = true
    @Published public var appNotificationSettings: [String: Bool] = [:]

    /// Appearance mode: "dark", "light", or "auto"
    @Published public var appearanceMode: String = "auto" {
        didSet {
            UserDefaults.standard.set(appearanceMode, forKey: "appearanceMode")
            applyAppearanceMode()
        }
    }
    
    private var batteryTimer: Timer?
    
    private let server = NetworkServer.shared
    private let beacon = DiscoveryBeacon()
    private let clipboard = ClipboardManager.shared
    private let networkMonitor = NetworkMonitor()
    private var sharedKey: Data = Data()
    
    public init() {
        let isTesting = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil || ProcessInfo.processInfo.processName == "xctest"
        self.sharedKey = isTesting ? Data(repeating: 0, count: 32) : KeychainHelper.getOrCreateSharedKey()
        self.downloadsDirectoryPath = FileManagerHelper.shared.getDownloadsDirectory().path
        let shouldHide = UserDefaults.standard.bool(forKey: "hideFromDock")
        self.hideFromDock = shouldHide
        
        self.showAndroidNotifications = UserDefaults.standard.object(forKey: "showAndroidNotifications") as? Bool ?? true
        self.showNotificationPreviews = UserDefaults.standard.object(forKey: "showNotificationPreviews") as? Bool ?? true

        // Load appearance mode
        let savedMode = UserDefaults.standard.string(forKey: "appearanceMode")
        self.appearanceMode = savedMode ?? "auto"

        if isTesting {
            return
        }

        applyAppearanceMode()
        
        NSApp.setActivationPolicy(shouldHide ? .accessory : .regular)
        
        self.updatePairingJson()
        self.checkLoginItemStatus()
        
        server.delegate = self
        networkMonitor.delegate = self
        
        startServices()
        networkMonitor.start()
    }
    
    public func startServices() {
        LogManager.shared.log("Starting Bridge host services")
        server.start(key: sharedKey)
        beacon.start()
        clipboard.updateAwakeStatus(isAwake: true)
    }

    private func applyAppearanceMode() {
        switch appearanceMode {
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        default: NSApp.appearance = nil
        }
    }
    
    public func restartServices() {
        LogManager.shared.log("Restarting Bridge host services")
        server.restart(key: sharedKey)
        if !isConnected {
            beacon.start()
        }
    }
    
    public func disconnect() {
        LogManager.shared.log("User disconnected phone")
        beacon.stop()
        server.disconnectClient(reason: "user_disconnected")
    }
    
    public func reconnect() {
        LogManager.shared.log("User triggered reconnect")
        server.isManuallyDisconnected = false
        server.stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self = self else { return }
            self.server.start(key: self.sharedKey)
            self.beacon.start()
        }
    }
    
    public func clearAndResetPairing() {
        LogManager.shared.log("User cleared pairing")
        server.disconnectClient(reason: "unpaired")
        beacon.stop()
        UserDefaults.standard.removeObject(forKey: "connectedDeviceName")
        self.connectedDeviceName = ""
        self.hasPairedDevice = false
        self.peerBatteryLevel = nil
        self.isPeerCharging = false
        self.deviceIpAddress = "—"
        self.regenerateKey()
    }

    public func recordClipboardSnippet(text: String, origin: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let isLink = trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")
        for i in 0..<recentClipboardSnippets.count {
            recentClipboardSnippets[i].isCurrent = false
        }
        let item = ClipboardSnippetItem(text: text, origin: origin, date: Date(), isLink: isLink, isCurrent: true)
        recentClipboardSnippets.insert(item, at: 0)
        if recentClipboardSnippets.count > 30 {
            recentClipboardSnippets.removeLast()
        }
    }

    public func clearClipboardHistory() {
        recentClipboardSnippets.removeAll()
    }

    public func copySnippet(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
    }

    private func updatePairingJson() {
        let machineName = Host.current().localizedName ?? "Mac"
        let base64Key = sharedKey.base64EncodedString()
        let info = PairingInfo(v: 1, name: machineName, key: base64Key, port: 48250)
        if let data = try? JSONEncoder().encode(info), let string = String(data: data, encoding: .utf8) {
            self.pairingJson = string
        }
        if sharedKey.count >= 4 {
            let num1 = (UInt32(sharedKey[0]) << 8 | UInt32(sharedKey[1])) % 900 + 100
            let num2 = (UInt32(sharedKey[2]) << 8 | UInt32(sharedKey[3])) % 900 + 100
            self.pairingCode = "\(num1) \(num2)"
        } else {
            self.pairingCode = "482 916"
        }
    }
    
    public func regenerateKey() {
        var randomBytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, randomBytes.count, &randomBytes)
        let newKey = Data(randomBytes)
        if KeychainHelper.saveSharedKey(newKey) {
            self.sharedKey = newKey
            updatePairingJson()
            restartServices()
            LogManager.shared.log("Regenerated new pairing key")
        }
    }
    
    // MARK: - File Transfer Methods
    public func chooseDownloadsDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        panel.prompt = "Select Download Folder"
        panel.message = "Choose where files received from your Android phone will be saved"
        
        if panel.runModal() == .OK, let selectedUrl = panel.url {
            FileManagerHelper.shared.setDownloadsDirectory(selectedUrl)
            self.downloadsDirectoryPath = selectedUrl.path
        }
    }
    
    public func openDownloadsDirectory() {
        let folder = FileManagerHelper.shared.getDownloadsDirectory()
        NSWorkspace.shared.open(folder)
    }
    
    public func selectAndSendFiles() {
        guard isConnected else {
            LogManager.shared.log("Cannot send file: Android phone not connected", level: .error)
            return
        }
        
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        panel.prompt = "Send to Android"
        panel.message = "Choose files to send to your phone"
        
        if panel.runModal() == .OK {
            for url in panel.urls {
                sendFile(url: url)
            }
        }
    }
    
    public func sendFiles(urls: [URL]) {
        for url in urls {
            sendFile(url: url)
        }
    }
    
    public func sendFile(url: URL) {
        guard isConnected else {
            LogManager.shared.log("Cannot send file: Android phone is not connected", level: .error)
            return
        }
        
        let name = url.lastPathComponent
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        let transferId = UUID().uuidString
        
        let newItem = FileTransferItem(
            id: transferId,
            name: name,
            size: size,
            url: url,
            isIncoming: false,
            progress: 0.0,
            status: "Sending…"
        )
        recentTransfers.insert(newItem, at: 0)
        isTransferring = true
        activeTransferName = name
        activeTransferProgress = 0.0
        
        server.sendFile(url: url, progressHandler: { [weak self] progress in
            guard let self = self else { return }
            self.activeTransferProgress = progress
            if let idx = self.recentTransfers.firstIndex(where: { $0.id == transferId }) {
                self.recentTransfers[idx].progress = progress
            }
        }, completion: { [weak self] result in
            guard let self = self else { return }
            self.isTransferring = false
            if let idx = self.recentTransfers.firstIndex(where: { $0.id == transferId }) {
                switch result {
                case .success:
                    self.recentTransfers[idx].status = "Sent"
                    self.recentTransfers[idx].progress = 1.0
                    LogManager.shared.log("Successfully sent \(name) to phone")
                case .failure(let error):
                    let isCancelled = (error as NSError).code == -999
                    self.recentTransfers[idx].status = isCancelled ? "Cancelled" : "Failed"
                    if isCancelled {
                        LogManager.shared.log("File transfer of \(name) cancelled by user")
                    } else {
                        LogManager.shared.log("Failed to send \(name): \(error)", level: .error)
                    }
                }
            }
        })
    }
    
    public func clearRecentTransfers() {
        recentTransfers.removeAll()
        LogManager.shared.log("Cleared recent file transfers")
    }
    
    public func clearNotificationHistory() {
        recentNotifications.removeAll()
        LogManager.shared.log("Cleared notification history")
    }

    public func dismissNotification(_ item: NotificationHistoryItem) {
        MacNotificationManager.shared.removeNotification(key: item.id)
        recentNotifications.removeAll { $0.id == item.id }
    }

    public func dismissAllNotifications() {
        recentNotifications.forEach { MacNotificationManager.shared.removeNotification(key: $0.id) }
        clearNotificationHistory()
    }

    public func syncClipboardNow() {
        guard isConnected else { return }
        clipboard.syncCurrentClipboard()
    }
    
    public func cancelActiveTransfer() {
        LogManager.shared.log("User cancelled active transfer: \(activeTransferName)")
        server.cancelActiveTransfer()
        isTransferring = false
        activeTransferProgress = 0.0
        if let idx = recentTransfers.firstIndex(where: { $0.status == "Sending…" || $0.status == "Receiving…" }) {
            recentTransfers[idx].status = "Cancelled"
        }
    }
    
    // MARK: - NetworkServerDelegate
    public nonisolated func serverDidUpdateConnectionStatus(isConnected: Bool, clientDescription: String?) {
        Task { @MainActor in
            self.isConnected = isConnected
            self.clientDescription = clientDescription
            self.clipboard.updateConnectionStatus(isConnected: isConnected)
            
            if isConnected {
                self.beacon.stop()
                if let desc = clientDescription {
                    let cleanIp = desc.components(separatedBy: ":").first ?? desc
                    if !cleanIp.isEmpty {
                        self.deviceIpAddress = cleanIp
                    }
                }
                self.sendLocalBatteryStatus()
                self.startBatterySyncTimer()
            } else {
                if !self.server.isManuallyDisconnected && self.hasPairedDevice {
                    self.beacon.start()
                }
                self.stopBatterySyncTimer()
                self.peerBatteryLevel = nil
                self.isPeerCharging = false
                self.deviceIpAddress = "—"
            }
        }
    }
    
    public nonisolated func serverDidReceiveText(text: String, hash: String) {
        clipboard.handleRemoteTextReceived(text, hash: hash)
        Task { @MainActor in
            self.recordClipboardSnippet(text: text, origin: "\(self.connectedDeviceName) → Mac")
        }
    }
    
    public nonisolated func serverDidReceiveImage(data: Data, hash: String) {
        clipboard.handleRemoteImageReceived(data, hash: hash)
    }
    
    public nonisolated func serverDidReceiveFile(name: String, savedUrl: URL, size: Int) {
        Task { @MainActor in
            if let idx = self.recentTransfers.firstIndex(where: { $0.name == name && $0.isIncoming }) {
                self.recentTransfers[idx].size = size
                self.recentTransfers[idx].url = savedUrl
                self.recentTransfers[idx].progress = 1.0
                self.recentTransfers[idx].status = "Saved to Downloads"
            } else {
                let item = FileTransferItem(
                    name: name,
                    size: size,
                    url: savedUrl,
                    isIncoming: true,
                    progress: 1.0,
                    status: "Saved to Downloads"
                )
                self.recentTransfers.insert(item, at: 0)
            }
            self.isTransferring = false
        }
    }
    
    public nonisolated func serverFileTransferProgress(transferId: String, name: String, progress: Double, isIncoming: Bool) {
        Task { @MainActor in
            self.isTransferring = progress < 1.0
            self.activeTransferName = name
            self.activeTransferProgress = progress
            
            if let idx = self.recentTransfers.firstIndex(where: { $0.name == name && $0.isIncoming == isIncoming }) {
                self.recentTransfers[idx].progress = progress
                if progress >= 1.0 {
                    self.recentTransfers[idx].status = isIncoming ? "Saved" : "Sent"
                }
            } else if isIncoming && progress < 1.0 {
                let newItem = FileTransferItem(
                    id: transferId,
                    name: name,
                    size: 0,
                    isIncoming: true,
                    progress: progress,
                    status: "Receiving…"
                )
                self.recentTransfers.insert(newItem, at: 0)
            }
        }
    }
    
    public nonisolated func serverDidReceiveNotificationPost(_ msg: NotifPostMessage) {
        Task { @MainActor in
            self.recordNotification(msg)
            MacNotificationManager.shared.handleNotificationPost(
                msg,
                showNotifications: self.showAndroidNotifications,
                showPreviews: self.showNotificationPreviews
            )
        }
    }
    
    private func recordNotification(_ msg: NotifPostMessage) {
        let displayTitle: String
        if let sender = msg.sender, !sender.trimmingCharacters(in: .whitespaces).isEmpty {
            displayTitle = sender
        } else if !msg.title.trimmingCharacters(in: .whitespaces).isEmpty {
            displayTitle = msg.title
        } else {
            displayTitle = msg.app
        }
        
        let bodyText = (msg.bigText?.isEmpty == false) ? msg.bigText! : msg.text
        let item = NotificationHistoryItem(
            id: msg.key,
            app: msg.app.isEmpty ? "Phone" : msg.app,
            title: displayTitle,
            body: bodyText,
            date: Date(timeIntervalSince1970: Double(msg.ts) / 1000.0),
            pkg: msg.pkg
        )
        if let idx = recentNotifications.firstIndex(where: { $0.id == msg.key }) {
            recentNotifications[idx] = item
        } else {
            recentNotifications.insert(item, at: 0)
            if recentNotifications.count > 50 {
                recentNotifications.removeLast()
            }
        }
    }
    
    public nonisolated func serverDidReceiveNotificationRemove(_ msg: NotifRemoveMessage) {
        Task { @MainActor in
            MacNotificationManager.shared.handleNotificationRemove(msg)
        }
    }
    
    public nonisolated func serverDidReceiveIconResponse(_ msg: IconRespMessage) {
        Task { @MainActor in
            MacNotificationManager.shared.handleIconResponse(msg)
        }
    }
    
    public nonisolated func serverDidReceiveCallEvent(_ msg: CallEventMessage) {
        Task { @MainActor in
            MacNotificationManager.shared.handleCallEvent(
                msg,
                showNotifications: self.showAndroidNotifications
            )
        }
    }
    
    public nonisolated func serverDidReceiveLockRequest() {
        Task { @MainActor in
            LogManager.shared.log("Executing remote lock screen request")
            self.lockMac()
        }
    }
    
    public nonisolated func serverDidReceiveBatteryStatus(_ msg: BatteryStatusMessage) {
        Task { @MainActor in
            self.peerBatteryLevel = msg.level
            self.isPeerCharging = msg.isCharging
            if let name = msg.deviceName, !name.trimmingCharacters(in: .whitespaces).isEmpty {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                self.connectedDeviceName = trimmed
                self.hasPairedDevice = true
                UserDefaults.standard.set(trimmed, forKey: "connectedDeviceName")
                LogManager.shared.log("Updated connected device name: \(trimmed)")
            } else if self.connectedDeviceName.isEmpty {
                self.connectedDeviceName = "Android Device"
                self.hasPairedDevice = true
                UserDefaults.standard.set("Android Device", forKey: "connectedDeviceName")
            }
            LogManager.shared.log("Received phone battery status: \(msg.level)%, isCharging=\(msg.isCharging)")
        }
    }
    
    public func sendLocalBatteryStatus() {
        guard isConnected else { return }
        if let info = MacBatteryMonitor.getCurrentBatteryInfo() {
            let msg = BatteryStatusMessage(level: info.level, isCharging: info.isCharging)
            server.send(type: .batteryStatus, value: msg)
            LogManager.shared.log("Sent Mac battery to Android: \(info.level)%, isCharging=\(info.isCharging)")
        }
    }
    
    private func startBatterySyncTimer() {
        batteryTimer?.invalidate()
        batteryTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.sendLocalBatteryStatus()
            }
        }
    }
    
    private func stopBatterySyncTimer() {
        batteryTimer?.invalidate()
        batteryTimer = nil
    }
    
    public func lockMac() {
        typealias SACLockScreenImmediateFunc = @convention(c) () -> Int32
        if let handle = dlopen("/System/Library/PrivateFrameworks/login.framework/login", RTLD_LAZY) {
            defer { dlclose(handle) }
            if let sym = dlsym(handle, "SACLockScreenImmediate") {
                let lockFunc = unsafeBitCast(sym, to: SACLockScreenImmediateFunc.self)
                let status = lockFunc()
                LogManager.shared.log("SACLockScreenImmediate executed with result \(status)")
                return
            }
        }
        
        LogManager.shared.log("SACLockScreenImmediate unavailable, falling back to pmset displaysleepnow")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        process.arguments = ["displaysleepnow"]
        try? process.run()
    }
    
    public func sendTestNotification() {
        MacNotificationManager.shared.sendTestNotification()
        let testItem = NotificationHistoryItem(
            id: UUID().uuidString,
            app: "Bridge Test",
            title: "Test Notification",
            body: "Bridge notification sync is working properly.",
            date: Date(),
            pkg: "com.bridge.android"
        )
        recentNotifications.insert(testItem, at: 0)
    }
    
    public func openNotificationSettings() {
        MacNotificationManager.shared.openSystemNotificationSettings()
    }
    
    // MARK: - NetworkMonitorDelegate
    public nonisolated func networkPathDidChange() {
        Task { @MainActor in
            // Force reconnect on network changes to handle IP changes, Wi-Fi drops, etc.
            LogManager.shared.log("Network path changed - forcing reconnect")
            self.server.forceReconnect(key: self.sharedKey)
            if !self.isConnected {
                self.beacon.start()
            }
        }
    }
    
    public nonisolated func systemWillSleep() {
        Task { @MainActor in
            self.clipboard.updateAwakeStatus(isAwake: false)
            self.beacon.stop()
            self.server.stop()
        }
    }
    
    public nonisolated func systemDidWake() {
        Task { @MainActor in
            self.clipboard.updateAwakeStatus(isAwake: true)
            // Force full reconnect after wake - ensures fresh connection
            LogManager.shared.log("System woke - forcing reconnect")
            self.server.forceReconnect(key: self.sharedKey)
            if !self.isConnected {
                self.beacon.start()
            }
        }
    }
    
    // MARK: - Open at Login
    private func checkLoginItemStatus() {
        if #available(macOS 13.0, *) {
            self.isOpenAtLogin = SMAppService.mainApp.status == .enabled
        }
    }
    
    public func toggleOpenAtLogin() {
        if #available(macOS 13.0, *) {
            do {
                if isOpenAtLogin {
                    try SMAppService.mainApp.unregister()
                    isOpenAtLogin = false
                    LogManager.shared.log("Unregistered from open at login")
                } else {
                    try SMAppService.mainApp.register()
                    isOpenAtLogin = true
                    LogManager.shared.log("Registered for open at login")
                }
            } catch {
                LogManager.shared.log("Failed to toggle open at login: \(error)", level: .error)
            }
        }
    }
    
    public func copyDiagnostics() {
        let logs = LogManager.shared.getDiagnostics()
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(logs, forType: .string)
        LogManager.shared.log("Diagnostics copied to pasteboard")
    }
    
    public func copyPairingJson() {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(pairingJson, forType: .string)
        LogManager.shared.log("Pairing code copied to pasteboard")
    }
}
