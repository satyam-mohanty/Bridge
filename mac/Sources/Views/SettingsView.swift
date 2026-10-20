import SwiftUI

public struct SettingsView: View {
    @ObservedObject var model: AppModel
    
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
        }
        .frame(width: 500, height: 320)
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
                Button("Send test notification") {
                    model.sendTestNotification()
                }
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
        }
        .formStyle(.grouped)
        .padding()
    }
}
