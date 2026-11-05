import SwiftUI
import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: AppModel?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        UserDefaults.standard.register(defaults: [
            "NSScreenLayoutAdjustOnWindowMove": false
        ])
        UserDefaults.standard.set(false, forKey: "NSScreenLayoutAdjustOnWindowMove")
        
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        _ = MacNotificationManager.shared
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
        .defaultSize(width: 1040, height: 740)
        .commands {
            CommandGroup(replacing: .appInfo) {
                Button("About Bridge") {
                    NSApp.activate(ignoringOtherApps: true)
                    for window in NSApp.windows {
                        if window.canBecomeMain {
                            window.makeKeyAndOrderFront(nil)
                            break
                        }
                    }
                    NotificationCenter.default.post(name: .openAboutPage, object: nil)
                }
            }
            CommandGroup(after: .appInfo) {
                Button("Check for Updates…") {
                    updateManager.checkForUpdates()
                }
                .disabled(!updateManager.canCheckForUpdates)
            }
        }
        
        MenuBarExtra {
            MenuBarView(model: model)
        } label: {
            menuBarIcon(isConnected: model.isConnected)
        }
        .menuBarExtraStyle(.window)
        
        Settings {
            SettingsView(model: model)
        }
    }
    
    private func menuBarIcon(isConnected: Bool) -> Image {
        let name = isConnected ? "bridge_menu_connected" : "bridge_menu_not_connected"
        if let image = NSImage(named: name) {
            let resized = image.copy() as! NSImage
            resized.size = NSSize(width: 19, height: 19)
            resized.isTemplate = true
            return Image(nsImage: resized)
        }
        return Image(name)
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
