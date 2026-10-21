import Foundation
import Combine
import Sparkle

@MainActor
public final class UpdateManager: NSObject, ObservableObject {
    public static let shared = UpdateManager()
    
    private var updaterController: SPUStandardUpdaterController?
    private var cancellables = Set<AnyCancellable>()
    
    @Published public private(set) var canCheckForUpdates: Bool = false
    @Published public private(set) var isChecking: Bool = false
    @Published public private(set) var statusMessage: String = "Up to date"
    @Published public private(set) var lastCheckDate: Date?
    
    @Published public var automaticallyChecksForUpdates: Bool = true {
        didSet {
            guard let updater = updaterController?.updater else { return }
            if updater.automaticallyChecksForUpdates != automaticallyChecksForUpdates {
                updater.automaticallyChecksForUpdates = automaticallyChecksForUpdates
            }
        }
    }
    
    @Published public var automaticallyDownloadsUpdates: Bool = false {
        didSet {
            guard let updater = updaterController?.updater else { return }
            if updater.automaticallyDownloadsUpdates != automaticallyDownloadsUpdates {
                updater.automaticallyDownloadsUpdates = automaticallyDownloadsUpdates
            }
        }
    }
    
    public var currentVersionString: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "Version \(version) (\(build))"
    }
    
    public var feedURLString: String {
        return updaterController?.updater.feedURL?.absoluteString
            ?? (Bundle.main.infoDictionary?["SUFeedURL"] as? String)
            ?? "Not configured"
    }
    
    public var hasPublicEdKeyConfigured: Bool {
        if let key = Bundle.main.infoDictionary?["SUPublicEDKey"] as? String, !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return true
        }
        return false
    }
    
    public override init() {
        super.init()
        setupUpdater()
    }
    
    private func setupUpdater() {
        // Initialize Sparkle standard updater controller without starting immediately
        // so we can safely check configuration before starting the updater engine.
        let controller = SPUStandardUpdaterController(
            startingUpdater: false,
            updaterDelegate: self,
            userDriverDelegate: nil
        )
        self.updaterController = controller
        
        let updater = controller.updater
        
        // Sync initial preferences
        self.automaticallyChecksForUpdates = updater.automaticallyChecksForUpdates
        self.automaticallyDownloadsUpdates = updater.automaticallyDownloadsUpdates
        self.lastCheckDate = updater.lastUpdateCheckDate
        
        // Bind to Sparkle's canCheckForUpdates publisher
        updater.publisher(for: \.canCheckForUpdates)
            .receive(on: RunLoop.main)
            .assign(to: &$canCheckForUpdates)
        
        // Bind to last update check date
        updater.publisher(for: \.lastUpdateCheckDate)
            .receive(on: RunLoop.main)
            .assign(to: &$lastCheckDate)
        
        if hasPublicEdKeyConfigured {
            controller.startUpdater()
            LogManager.shared.log("UpdateManager initialized and started. Feed: \(feedURLString).")
        } else {
            statusMessage = "EdDSA public key pending"
            LogManager.shared.log("UpdateManager initialized in development mode (no SUPublicEDKey configured). Updater will start once key is provided.")
        }
    }
    
    public func checkForUpdates() {
        guard hasPublicEdKeyConfigured else {
            LogManager.shared.log("UpdateManager: cannot check for updates without SUPublicEDKey configured")
            statusMessage = "Sparkle EdDSA public key not configured"
            return
        }
        LogManager.shared.log("UpdateManager: user initiated manual update check")
        isChecking = true
        statusMessage = "Checking for updates…"
        updaterController?.checkForUpdates(nil)
    }
    
    public func checkForUpdatesInBackground() {
        guard hasPublicEdKeyConfigured else { return }
        LogManager.shared.log("UpdateManager: running background update check")
        updaterController?.updater.checkForUpdatesInBackground()
    }
}

// MARK: - SPUUpdaterDelegate
extension UpdateManager: SPUUpdaterDelegate {
    public func feedURLString(for updater: SPUUpdater) -> String? {
        // Fall back to Info.plist SUFeedURL
        return nil
    }
    
    public func updater(_ updater: SPUUpdater, didFinishLoading appcast: SUAppcast) {
        LogManager.shared.log("UpdateManager: appcast loaded successfully with \(appcast.items.count) item(s)")
    }
    
    public func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) {
        LogManager.shared.log("UpdateManager: found valid update: \(item.displayVersionString) (build \(item.versionString))")
        Task { @MainActor in
            self.isChecking = false
            self.statusMessage = "Update available: \(item.displayVersionString)"
        }
    }
    
    public func updaterDidNotFindUpdate(_ updater: SPUUpdater, error: Error) {
        LogManager.shared.log("UpdateManager: no update found or check completed: \(error.localizedDescription)")
        Task { @MainActor in
            self.isChecking = false
            self.statusMessage = "Bridge is up to date"
        }
    }
    
    public func updaterDidNotFindUpdate(_ updater: SPUUpdater) {
        LogManager.shared.log("UpdateManager: Bridge is up to date.")
        Task { @MainActor in
            self.isChecking = false
            self.statusMessage = "Bridge is up to date"
        }
    }
    
    public func updater(_ updater: SPUUpdater, willDownloadUpdate item: SUAppcastItem, with request: NSMutableURLRequest) {
        LogManager.shared.log("UpdateManager: downloading update \(item.displayVersionString)")
        Task { @MainActor in
            self.statusMessage = "Downloading \(item.displayVersionString)…"
        }
    }
    
    public func updater(_ updater: SPUUpdater, didDownloadUpdate item: SUAppcastItem) {
        LogManager.shared.log("UpdateManager: downloaded update \(item.displayVersionString) successfully")
        Task { @MainActor in
            self.statusMessage = "Update ready to install"
        }
    }
    
    public func updater(_ updater: SPUUpdater, failedToDownloadUpdate item: SUAppcastItem, error: Error) {
        LogManager.shared.log("UpdateManager: failed to download update: \(error.localizedDescription)")
        Task { @MainActor in
            self.isChecking = false
            self.statusMessage = "Download failed: \(error.localizedDescription)"
        }
    }
    
    public func updater(_ updater: SPUUpdater, willInstallUpdate item: SUAppcastItem) {
        LogManager.shared.log("UpdateManager: preparing to install update \(item.displayVersionString)")
        Task { @MainActor in
            self.statusMessage = "Installing update…"
        }
    }
    
    public func updaterWillRelaunchApplication(_ updater: SPUUpdater) {
        LogManager.shared.log("UpdateManager: relaunching application after update")
    }
    
    public func updater(_ updater: SPUUpdater, didAbortWithError error: Error) {
        LogManager.shared.log("UpdateManager: aborted with error: \(error.localizedDescription)")
        Task { @MainActor in
            self.isChecking = false
            self.statusMessage = "Update error: \(error.localizedDescription)"
        }
    }
    
    public func updater(_ updater: SPUUpdater, didFinishUpdateCycleFor updateCheck: SPUUpdateCheck, error: Error?) {
        Task { @MainActor in
            self.isChecking = false
            if let error = error {
                LogManager.shared.log("UpdateManager: finished cycle with error: \(error.localizedDescription)")
            } else {
                LogManager.shared.log("UpdateManager: finished update check cycle")
            }
        }
    }
}
