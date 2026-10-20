import Foundation
import SwiftUI
import ServiceManagement
import AppKit

public struct FileTransferItem: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let size: Int
    public let url: URL?
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
    
    public init(id: String = UUID().uuidString, app: String, title: String, body: String, date: Date = Date(), pkg: String = "") {
        self.id = id
        self.app = app
        self.title = title
        self.body = body
        self.date = date
        self.pkg = pkg
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
        
        if isTesting {
            return
        }
        
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
    
    public func restartServices() {
        LogManager.shared.log("Restarting Bridge host services")
        server.restart(key: sharedKey)
        if !isConnected {
            beacon.start()
        }
    }
    
    public func disconnect() {
        LogManager.shared.log("User disconnected phone")
        server.disconnectClient()
    }
    
    public func reconnect() {
        LogManager.shared.log("User triggered reconnect")
        server.stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self = self else { return }
            self.server.start(key: self.sharedKey)
            self.beacon.start()
        }
    }
    
    public func clearAndResetPairing() {
        LogManager.shared.log("User cleared pairing")
        disconnect()
        regenerateKey()
    }
    
    private func updatePairingJson() {
        let machineName = Host.current().localizedName ?? "Mac"
        let base64Key = sharedKey.base64EncodedString()
        let info = PairingInfo(v: 1, name: machineName, key: base64Key, port: 48250)
        if let data = try? JSONEncoder().encode(info), let string = String(data: data, encoding: .utf8) {
            self.pairingJson = string
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
            } else {
                self.beacon.start()
            }
        }
    }
    
    public nonisolated func serverDidReceiveText(text: String, hash: String) {
        clipboard.handleRemoteTextReceived(text, hash: hash)
    }
    
    public nonisolated func serverDidReceiveImage(data: Data, hash: String) {
        clipboard.handleRemoteImageReceived(data, hash: hash)
    }
    
    public nonisolated func serverDidReceiveFile(name: String, savedUrl: URL, size: Int) {
        Task { @MainActor in
            let item = FileTransferItem(
                name: name,
                size: size,
                url: savedUrl,
                isIncoming: true,
                progress: 1.0,
                status: "Saved to Downloads"
            )
            self.recentTransfers.insert(item, at: 0)
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
            self.restartServices()
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
            self.restartServices()
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
