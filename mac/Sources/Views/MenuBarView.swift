import SwiftUI

public struct MenuBarView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var updateManager = UpdateManager.shared
    @State private var diagnosticsCopied = false
    
    public init(model: AppModel) {
        self.model = model
    }
    
    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Status Header
            HStack(spacing: 12) {
                if let logoUrl = Bundle.main.url(forResource: "BridgeLogo", withExtension: "png"),
                   let image = NSImage(contentsOf: logoUrl) {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 32, height: 32)
                }
                
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(model.isConnected ? Color.green : Color.orange)
                            .frame(width: 8, height: 8)
                        Text(model.isConnected ? "Connected" : "Listening...")
                            .font(.headline)
                    }
                    Text("Port 48250 • Beacon active")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal, 4)
            .padding(.top, 4)
            
            Divider()
            
            // Primary Actions
            Button(action: {
                NSApp.activate(ignoringOtherApps: true)
                for window in NSApp.windows {
                    if window.canBecomeMain {
                        window.makeKeyAndOrderFront(nil)
                        return
                    }
                }
            }) {
                Label("Open Bridge Window", systemImage: "macwindow")
            }
            .buttonStyle(.plain)
            
            Button(action: {
                NSApp.activate(ignoringOtherApps: true)
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }) {
                Label("Settings…", systemImage: "gear")
            }
            .buttonStyle(.plain)
            
            Button(action: {
                updateManager.checkForUpdates()
            }) {
                Label("Check for Updates…", systemImage: "arrow.triangle.2.circlepath")
            }
            .buttonStyle(.plain)
            .disabled(!updateManager.canCheckForUpdates)
            
            Button(action: {
                model.selectAndSendFiles()
            }) {
                Label("Send Files to Android…", systemImage: "paperplane")
            }
            .buttonStyle(.plain)
            .disabled(!model.isConnected)
            
            Button(action: {
                PairingWindowManager.shared.show(model: model)
            }) {
                Label("Pair New Phone…", systemImage: "qrcode")
            }
            .buttonStyle(.plain)
            
            Divider()
            
            // Connection Controls
            if model.isConnected {
                Button(action: {
                    model.disconnect()
                }) {
                    Label("Disconnect Phone", systemImage: "bolt.slash")
                }
                .buttonStyle(.plain)
            }
            
            Button(action: {
                model.reconnect()
            }) {
                Label("Reconnect", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.plain)
            
            Button(role: .destructive, action: {
                model.clearAndResetPairing()
            }) {
                Label("Clear & Reset Pairing", systemImage: "trash")
            }
            .buttonStyle(.plain)
            
            Divider()
            
            // App Preferences
            Toggle("Hide from Dock", isOn: $model.hideFromDock)
            
            Toggle("Open at Login", isOn: Binding(
                get: { model.isOpenAtLogin },
                set: { _ in model.toggleOpenAtLogin() }
            ))
            
            Divider()
            
            Link(destination: URL(string: "https://buymeacoffee.com/satyammohanty")!) {
                Label("Buy Me a Coffee", systemImage: "cup.and.saucer.fill")
            }
            .buttonStyle(.plain)
            
            Button(action: {
                model.copyDiagnostics()
                diagnosticsCopied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    diagnosticsCopied = false
                }
            }) {
                Label(diagnosticsCopied ? "Diagnostics Copied!" : "Copy Diagnostics", systemImage: diagnosticsCopied ? "checkmark" : "doc.on.clipboard")
            }
            .buttonStyle(.plain)
            
            Divider()
            
            Button("Quit Bridge") {
                NSApplication.shared.terminate(nil)
            }
            .buttonStyle(.plain)
            .keyboardShortcut("q")
        }
        .padding(12)
        .frame(width: 240)
    }
}
