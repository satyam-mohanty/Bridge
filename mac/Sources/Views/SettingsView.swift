import SwiftUI

public struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var updateManager = UpdateManager.shared
    
    public init(model: AppModel) {
        self.model = model
    }
    
    public var body: some View {
        TabView {
            generalTab
                .tabItem {
                    Label("General", systemImage: "gear")
                }
                .tag(0)

            notificationsTab
                .tabItem {
                    Label("Notifications", systemImage: "bell.badge")
                }
                .tag(1)

            aboutTab
                .tabItem {
                    Label("About", systemImage: "info.circle")
                }
                .tag(2)
        }
        .frame(width: 520, height: 500)
    }
    
    public var notificationsTab: some View {
        Form {
            Section {
                Toggle("Show Android notifications", isOn: $model.showAndroidNotifications)
                
                Toggle("Show previews", isOn: $model.showNotificationPreviews)
                    .disabled(!model.showAndroidNotifications)
            } header: {
                Text("Phone Notifications")
            } footer: {
                Text("When enabled, allowed notifications from your Android phone appear as native macOS alerts.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Section {
                if model.recentNotifications.isEmpty {
                    Text("No recent notifications")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            ForEach(model.recentNotifications) { notif in
                                Button {
                                    MacNotificationManager.shared.handleNotificationClick(pkg: notif.pkg, appName: notif.app)
                                } label: {
                                    HStack(alignment: .top, spacing: 10) {
                                        Image(systemName: "app.badge.fill")
                                            .foregroundColor(.accentColor)
                                            .font(.subheadline)
                                            .padding(.top, 2)
                                        
                                        VStack(alignment: .leading, spacing: 2) {
                                            HStack {
                                                Text(notif.app)
                                                    .font(.caption)
                                                    .fontWeight(.semibold)
                                                    .foregroundColor(.primary)
                                                Spacer()
                                                Text(notif.date, style: .time)
                                                    .font(.caption2)
                                                    .foregroundColor(.secondary)
                                            }
                                            Text(notif.title)
                                                .font(.subheadline)
                                                .fontWeight(.medium)
                                                .lineLimit(1)
                                            if !notif.body.isEmpty {
                                                Text(notif.body)
                                                    .font(.caption)
                                                    .foregroundColor(.secondary)
                                                    .lineLimit(2)
                                            }
                                        }
                                    }
                                    .contentShape(Rectangle())
                                    .padding(.vertical, 3)
                                }
                                .buttonStyle(.plain)
                                
                                if notif.id != model.recentNotifications.last?.id {
                                    Divider()
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .frame(maxHeight: 150)
                }
            } header: {
                HStack {
                    Text("Recent Notifications")
                    Spacer()
                    if !model.recentNotifications.isEmpty {
                        Button("Clear") {
                            model.clearNotificationHistory()
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.accentColor)
                        .font(.caption)
                    }
                }
            }
            
            Section {
                Button("Send test notification") {
                    model.sendTestNotification()
                }
                
                Button("Open macOS Notification Settings") {
                    model.openNotificationSettings()
                }
            } footer: {
                Text("If system banners do not appear, ensure Bridge is allowed in System Settings > Notifications.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
    
    public var generalTab: some View {
        Form {
            Section {
                HStack {
                    Text("Save received files to:")
                    Spacer()
                    Text(model.downloadsDirectoryPath)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .foregroundColor(.secondary)
                    Button("Change…") {
                        model.chooseDownloadsDirectory()
                    }
                }
            } header: {
                Text("Downloads")
            }
            
            Section {
                Toggle("Hide Bridge from Dock", isOn: $model.hideFromDock)
                Toggle("Launch Bridge at Login", isOn: Binding(
                    get: { model.isOpenAtLogin },
                    set: { _ in model.toggleOpenAtLogin() }
                ))
            } header: {
                Text("Application")
            }

            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Setup & Onboarding Guide")
                            .font(.subheadline)
                            .fontWeight(.medium)
                        Text("Revisit the step-by-step setup walkthrough for permissions and pairing.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("Open Guide") {
                        model.replayOnboarding()
                    }
                }
            } header: {
                Text("Onboarding")
            }
            
            Section {
                Toggle("Automatically check for updates", isOn: $updateManager.automaticallyChecksForUpdates)
                Toggle("Automatically download updates", isOn: $updateManager.automaticallyDownloadsUpdates)
                    .disabled(!updateManager.automaticallyChecksForUpdates)
                
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(updateManager.currentVersionString)
                            .font(.subheadline)
                            .foregroundColor(.primary)
                        if let date = updateManager.lastCheckDate {
                            Text("Last checked: \(date, style: .date) \(date, style: .time)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        } else {
                            Text("Status: \(updateManager.statusMessage)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    
                    Spacer()
                    
                    Button(action: {
                        updateManager.checkForUpdates()
                    }) {
                        if updateManager.isChecking {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text("Check Now")
                        }
                    }
                    .disabled(!updateManager.canCheckForUpdates || updateManager.isChecking)
                }
            } header: {
                Text("Software Updates")
            } footer: {
                Text("Updates are delivered over secure HTTPS and verified cryptographically with EdDSA signatures.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
    
    public var aboutTab: some View {
        Form {
            Section {
                HStack(spacing: 16) {
                    if let image = NSImage(named: "BridgeLogo") {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 44, height: 44)
                    }
                    
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Bridge")
                            .font(.headline)
                        Text(updateManager.currentVersionString)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                    
                    Button(action: {
                        updateManager.checkForUpdates()
                    }) {
                        if updateManager.isChecking {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Text("Check Now")
                        }
                    }
                    .disabled(!updateManager.canCheckForUpdates || updateManager.isChecking)
                }
                .padding(.vertical, 4)
            }
            
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Built by Satyam Mohanty, an independent developer who builds cool things.")
                        .font(.subheadline)
                        .fontWeight(.medium)
                    
                    HStack(spacing: 12) {
                        Link("Website", destination: URL(string: "https://www.satyammohanty.com")!)
                        Text("•").foregroundColor(.secondary)
                        Link("GitHub", destination: URL(string: "https://github.com/satyam-mohanty")!)
                        Text("•").foregroundColor(.secondary)
                        Link("X", destination: URL(string: "https://x.com/isatymm")!)
                        Text("•").foregroundColor(.secondary)
                        Link("LinkedIn", destination: URL(string: "https://www.linkedin.com/in/satyam-mohanty")!)
                    }
                    .font(.caption)
                    .padding(.top, 2)
                }
                .padding(.vertical, 2)
            } header: {
                Text("Developer")
            }
            
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Zero Telemetry & Zero Cloud")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                        Text("Bridge contains no telemetry, analytics, or third-party trackers. All communication stays on your local Wi-Fi network.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Divider()
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Authenticated End-to-End Encryption")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                        Text("Data packets are encrypted using AES-256-GCM. Cryptographic keys are saved securely in macOS Keychain.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Divider()
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Local Storage & Notification Previews")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                        Text("Notification and clipboard history stay strictly local. Previews can be masked at any time from Notifications preferences.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text("Privacy Policy")
            } footer: {
                Text("© 2026 Satyam Mohanty. All rights reserved.")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}
