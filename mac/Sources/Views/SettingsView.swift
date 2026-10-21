import SwiftUI

public struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var updateManager = UpdateManager.shared
    
    public init(model: AppModel) {
        self.model = model
    }
    
    public var body: some View {
        TabView {
            notificationsTab
                .tabItem {
                    Label("Notifications", systemImage: "bell.badge")
                }
                .tag(0)
            
            generalTab
                .tabItem {
                    Label("General", systemImage: "gear")
                }
                .tag(1)
        }
        .frame(width: 520, height: 480)
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
                                .padding(.vertical, 3)
                                
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
}
