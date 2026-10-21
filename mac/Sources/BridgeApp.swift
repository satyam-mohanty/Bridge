import SwiftUI
import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: AppModel?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
    }
    
    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        let urls = filenames.map { URL(fileURLWithPath: $0) }
        Task { @MainActor in
            self.model?.sendFiles(urls: urls)
        }
    }
    
    @objc func sendFilesViaBridge(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        var urls: [URL] = []
        if let items = pboard.pasteboardItems {
            for item in items {
                if let str = item.string(forType: .fileURL), let url = URL(string: str) {
                    urls.append(url)
                }
            }
        }
        if !urls.isEmpty {
            Task { @MainActor in
                self.model?.sendFiles(urls: urls)
            }
        }
    }
}

@main
struct BridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var model = AppModel()
    @StateObject private var updateManager = UpdateManager.shared
    
    var body: some Scene {
        WindowGroup("Bridge", id: "main") {
            MainWindowView(model: model)
                .preferredColorScheme(.dark)
                .onAppear {
                    appDelegate.model = model
                }
                .onOpenURL { url in
                    model.sendFiles(urls: [url])
                }
        }
        .windowStyle(.hiddenTitleBar)
        .windowToolbarStyle(.unified)
        .windowResizability(.contentMinSize)
        .defaultSize(width: 680, height: 640)
        .commands {
            CommandGroup(after: .appInfo) {
                Button("Check for Updates…") {
                    updateManager.checkForUpdates()
                }
                .disabled(!updateManager.canCheckForUpdates)
            }
        }
        
        MenuBarExtra("Bridge", systemImage: model.isConnected ? "link.circle.fill" : "link.circle") {
            MenuBarView(model: model)
        }
        .menuBarExtraStyle(.window)
        
        Settings {
            SettingsView(model: model)
        }
    }
}

@MainActor
public final class PairingWindowManager {
    public static let shared = PairingWindowManager()
    private var window: NSWindow?
    
    public func show(model: AppModel) {
        if let existing = window {
            existing.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        
        let qrView = PairingQRView(pairingJson: model.pairingJson, model: model)
        let hosting = NSHostingController(rootView: qrView)
        
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 440),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        win.title = "Bridge - Pair Phone"
        win.center()
        win.isReleasedWhenClosed = false
        win.contentViewController = hosting
        
        win.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.window = win
    }
}
