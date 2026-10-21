import SwiftUI
import AppKit
import UniformTypeIdentifiers

public struct MainWindowView: View {
    @ObservedObject var model: AppModel
    @State private var isDropTargeted: Bool = false
    @State private var showDiagnosticsSheet: Bool = false
    @State private var showPairingSheet: Bool = false
    @State private var showClearConfirm: Bool = false
    @State private var settingsTab: Int = 0
    @State private var showAllTransfers: Bool = false
    @State private var showAllNotifications: Bool = false
    
    // Liquid glass / Apple Design System Palette
    private let glassMaterial = Material.ultraThinMaterial
    private let glassFill = Color.white.opacity(0.06)
    private let glassFillHover = Color.white.opacity(0.09)
    private let glassBorder = Color.white.opacity(0.12)
    private let glassBorderSubtle = Color.white.opacity(0.07)
    private let bgCanvas = Color(red: 0.05, green: 0.05, blue: 0.05)
    
    public init(model: AppModel) {
        self.model = model
    }
    
    public var body: some View {
        ZStack {
            // Background deep canvas
            bgCanvas.ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Frosted Liquid Glass Top Bar
                headerSection
                    .padding(.horizontal, 28)
                    .padding(.top, 24)
                    .padding(.bottom, 18)
                    .background(.bar)
                
                Divider()
                    .overlay(glassBorderSubtle)
                
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 24) {
                        // Drop Zone / Send Files
                        dropZoneCard
                        
                        // Active Transfer Progress
                        if model.isTransferring {
                            activeTransferBanner
                        }
                        
                        // Recent Transfers
                        recentTransfersSection
                        
                        // Recent Notifications
                        recentNotificationsSection
                        
                        // Settings & Preferences
                        settingsSection
                        
                        // Footer Attribution & Socials
                        HStack {
                            HStack(spacing: 6) {
                                Text("© 2026 Bridge")
                                    .foregroundColor(.secondary)
                                Text("•")
                                    .foregroundColor(.secondary.opacity(0.4))
                                Text("Built by")
                                    .foregroundColor(.secondary)
                                Link("Satyam Mohanty", destination: URL(string: "https://www.satyammohanty.com")!)
                                    .foregroundColor(.accentColor)
                            }
                            
                            Spacer()
                            
                            HStack(spacing: 8) {
                                Link("X", destination: URL(string: "https://x.com/isatymm")!)
                                    .foregroundColor(.secondary)
                                Text("•")
                                    .foregroundColor(.secondary.opacity(0.3))
                                Link("LinkedIn", destination: URL(string: "https://www.linkedin.com/in/satyam-mohanty")!)
                                    .foregroundColor(.secondary)
                                Text("•")
                                    .foregroundColor(.secondary.opacity(0.3))
                                Link("GitHub", destination: URL(string: "https://github.com/satyam-mohanty")!)
                                    .foregroundColor(.secondary)
                                Text("•")
                                    .foregroundColor(.secondary.opacity(0.3))
                                Link("Instagram", destination: URL(string: "https://www.instagram.com/isatymm")!)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .font(.system(size: 11, weight: .regular))
                        .padding(.top, 4)
                        .padding(.bottom, 4)
                    }
                    .padding(.horizontal, 28)
                    .padding(.top, 24)
                    .padding(.bottom, 12)
                }
            }
        }
        .frame(minWidth: 640, idealWidth: 680, minHeight: 560, idealHeight: 640)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showPairingSheet) {
            PairingQRView(pairingJson: model.pairingJson, model: model)
        }
        .sheet(isPresented: $showDiagnosticsSheet) {
            diagnosticsSheetView
        }
    }
    
    // MARK: - Header
    private var headerSection: some View {
        HStack(spacing: 16) {
            if let logoUrl = Bundle.main.url(forResource: "BridgeLogo", withExtension: "png"),
               let image = NSImage(contentsOf: logoUrl) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 40, height: 40)
                    .cornerRadius(8)
            } else {
                Image(systemName: "link")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                    .frame(width: 40, height: 40)
            }
            
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text("Bridge")
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                    
                    Text("Continuity")
                        .font(.system(size: 11, weight: .semibold, design: .rounded))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.18))
                        .foregroundColor(.accentColor)
                        .clipShape(Capsule())
                }
                
                Text("Native AirDrop & Clipboard Sync")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            // Buy Me a Coffee Button
            Link(destination: URL(string: "https://buymeacoffee.com/satyammohanty")!) {
                HStack(spacing: 6) {
                    Image(systemName: "cup.and.saucer.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(Color(red: 1.0, green: 0.86, blue: 0.35))
                    Text("Buy Me a Coffee")
                        .font(.system(size: 11, weight: .semibold, design: .rounded))
                        .foregroundColor(.primary)
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(glassFill)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .help("Support Bridge development")
            
            // Connection Status Pill
            HStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill((model.isConnected ? Color.green : Color.orange).opacity(0.35))
                        .frame(width: 14, height: 14)
                    Circle()
                        .fill(model.isConnected ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                }
                
                Text(model.isConnected ? "Connected" : "Listening…")
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundColor(.primary)
                
                if model.isConnected, let phoneBattery = model.peerBatteryLevel {
                    Text("•")
                        .foregroundColor(.secondary.opacity(0.4))
                    
                    HStack(spacing: 3) {
                        Image(systemName: model.isPeerCharging ? "battery.100.bolt" : (phoneBattery > 20 ? "battery.75" : "battery.25"))
                            .font(.system(size: 12))
                            .foregroundColor(model.isPeerCharging ? .green : (phoneBattery <= 20 ? .orange : .secondary))
                        
                        Text("\(phoneBattery)%")
                            .font(.system(size: 12, weight: .medium, design: .rounded))
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(glassMaterial)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
            .shadow(color: Color.black.opacity(0.2), radius: 8, x: 0, y: 4)
        }
    }
    
    // MARK: - Drop Zone (Liquid Glass AirDrop Card)
    private var dropZoneCard: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(isDropTargeted ? Color.accentColor.opacity(0.25) : Color.accentColor.opacity(0.12))
                    .frame(width: 60, height: 60)
                
                Image(systemName: isDropTargeted ? "arrow.down.doc.fill" : "square.and.arrow.up.fill")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundColor(isDropTargeted ? Color.white : Color.accentColor)
            }
            .shadow(color: Color.accentColor.opacity(isDropTargeted ? 0.4 : 0.15), radius: 10, x: 0, y: 4)
            
            VStack(spacing: 4) {
                Text(isDropTargeted ? "Drop Files to Send" : "Send Files to Android")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundColor(.primary)
                
                Text("Drag and drop documents, photos, or videos here")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            Button(action: {
                model.selectAndSendFiles()
            }) {
                HStack(spacing: 6) {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Select Files…")
                        .font(.system(size: 13, weight: .semibold))
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 8)
                .background(Color.accentColor)
                .foregroundColor(.white)
                .clipShape(Capsule())
                .shadow(color: Color.accentColor.opacity(0.4), radius: 8, x: 0, y: 3)
            }
            .buttonStyle(.plain)
            .disabled(!model.isConnected)
            .opacity(model.isConnected ? 1.0 : 0.5)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .padding(.horizontal, 20)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(glassMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    isDropTargeted ? Color.accentColor : glassBorder,
                    lineWidth: isDropTargeted ? 2 : 1
                )
        )
        .shadow(color: Color.black.opacity(0.3), radius: 16, x: 0, y: 8)
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDroppedFiles(providers: providers)
            return true
        }
    }
    
    private func handleDroppedFiles(providers: [NSItemProvider]) {
        for provider in providers {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                    DispatchQueue.main.async {
                        model.sendFiles(urls: [url])
                    }
                } else if let url = item as? URL {
                    DispatchQueue.main.async {
                        model.sendFiles(urls: [url])
                    }
                }
            }
        }
    }
    
    // MARK: - Active Transfer Progress Banner
    private var activeTransferBanner: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.18))
                    .frame(width: 36, height: 36)
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.accentColor)
            }
            
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(model.activeTransferName)
                        .font(.system(size: 13, weight: .medium))
                        .lineLimit(1)
                    Spacer()
                    Text("\(Int(model.activeTransferProgress * 100))%")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                    
                    Button(action: {
                        model.cancelActiveTransfer()
                    }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                    .help("Cancel transfer")
                }
                
                ProgressView(value: model.activeTransferProgress)
                    .progressViewStyle(.linear)
                    .tint(.accentColor)
            }
        }
        .padding(16)
        .background(glassMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorder, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.2), radius: 10, x: 0, y: 4)
    }
    
    // MARK: - Recent Transfers Section
    private var recentTransfersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Label("Recent Transfers", systemImage: "arrow.left.arrow.right.circle")
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundColor(.secondary)
                
                Spacer()
                
                if model.recentTransfers.count > 2 {
                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showAllTransfers.toggle()
                        }
                    }) {
                        HStack(spacing: 4) {
                            Text(showAllTransfers ? "Show Less" : "Show All (\(model.recentTransfers.count))")
                            Image(systemName: showAllTransfers ? "chevron.up" : "chevron.down")
                                .font(.system(size: 9, weight: .semibold))
                        }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.accentColor)
                    }
                    .buttonStyle(.plain)
                    
                    Text("•")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary.opacity(0.3))
                }
                
                if !model.recentTransfers.isEmpty {
                    Button(action: {
                        model.clearRecentTransfers()
                    }) {
                        Text("Clear")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            
            if model.recentTransfers.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "tray")
                            .font(.system(size: 24))
                            .foregroundColor(.secondary.opacity(0.6))
                        Text("No file transfers yet")
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 24)
                    Spacer()
                }
                .background(glassMaterial.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
            } else {
                let displayedTransfers = showAllTransfers ? model.recentTransfers : Array(model.recentTransfers.prefix(2))
                VStack(spacing: 8) {
                    ForEach(displayedTransfers) { item in
                        transferRow(item: item)
                    }
                    
                    if model.recentTransfers.count > 2 {
                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                showAllTransfers.toggle()
                            }
                        }) {
                            HStack(spacing: 6) {
                                Text(showAllTransfers ? "Show Less" : "Show All (\(model.recentTransfers.count))")
                                    .font(.system(size: 11, weight: .medium))
                                Image(systemName: showAllTransfers ? "chevron.up" : "chevron.down")
                                    .font(.system(size: 9, weight: .semibold))
                            }
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(glassFill)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 2)
                    }
                }
            }
        }
    }
    
    private func transferRow(item: FileTransferItem) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill((item.isIncoming ? Color.green : Color.accentColor).opacity(0.16))
                    .frame(width: 32, height: 32)
                
                Image(systemName: item.isIncoming ? "arrow.down" : "arrow.up")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(item.isIncoming ? .green : .accentColor)
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                
                HStack(spacing: 6) {
                    Text(item.status)
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    if item.size > 0 {
                        Text("•")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                        Text(formatByteCount(item.size))
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }
            }
            
            Spacer()
            
            if let url = item.url, FileManager.default.fileExists(atPath: url.path) {
                Button(action: {
                    NSWorkspace.shared.activateFileViewerSelecting([url])
                }) {
                    Text("Show in Finder")
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(glassFill)
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(12)
        .background(glassMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
    }
    
    // MARK: - Recent Notifications Section
    private var recentNotificationsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Label("Recent Notifications", systemImage: "bell.badge")
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundColor(.secondary)
                
                Spacer()
                
                if model.recentNotifications.count > 2 {
                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showAllNotifications.toggle()
                        }
                    }) {
                        HStack(spacing: 4) {
                            Text(showAllNotifications ? "Show Less" : "Show All (\(model.recentNotifications.count))")
                            Image(systemName: showAllNotifications ? "chevron.up" : "chevron.down")
                                .font(.system(size: 9, weight: .semibold))
                        }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.accentColor)
                    }
                    .buttonStyle(.plain)
                    
                    Text("•")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary.opacity(0.3))
                }
                
                if !model.recentNotifications.isEmpty {
                    Button(action: {
                        model.clearNotificationHistory()
                    }) {
                        Text("Clear")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            
            if model.recentNotifications.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "bell.slash")
                            .font(.system(size: 24))
                            .foregroundColor(.secondary.opacity(0.6))
                        Text("No notifications from phone yet")
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 24)
                    Spacer()
                }
                .background(glassMaterial.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
            } else {
                let displayedNotifications = showAllNotifications ? model.recentNotifications : Array(model.recentNotifications.prefix(2))
                VStack(spacing: 8) {
                    ForEach(displayedNotifications) { notif in
                        notificationRow(item: notif)
                    }
                    
                    if model.recentNotifications.count > 2 {
                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                showAllNotifications.toggle()
                            }
                        }) {
                            HStack(spacing: 6) {
                                Text(showAllNotifications ? "Show Less" : "Show All (\(model.recentNotifications.count))")
                                    .font(.system(size: 11, weight: .medium))
                                Image(systemName: showAllNotifications ? "chevron.up" : "chevron.down")
                                    .font(.system(size: 9, weight: .semibold))
                            }
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(glassFill)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 2)
                    }
                }
            }
        }
    }
    
    private func notificationRow(item: NotificationHistoryItem) -> some View {
        Button {
            MacNotificationManager.shared.handleNotificationClick(pkg: item.pkg, appName: item.app)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(Color.accentColor.opacity(0.16))
                        .frame(width: 32, height: 32)
                    
                    Image(systemName: "bell.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.accentColor)
                }
                .padding(.top, 2)
                
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(item.app)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.primary)
                        Spacer()
                        Text(item.date, style: .time)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                    
                    if !item.title.isEmpty {
                        Text(item.title)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                    }
                    
                    if !item.body.isEmpty {
                        Text(item.body)
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                    }
                }
            }
            .contentShape(Rectangle())
            .padding(12)
            .background(glassMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
    
    // MARK: - Settings Section
    private var settingsSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Preferences", systemImage: "gearshape")
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundColor(.secondary)
                
                Spacer()
                
                Picker("", selection: $settingsTab) {
                    Text("General").tag(0)
                    Text("Notifications").tag(1)
                }
                .pickerStyle(.segmented)
                .frame(width: 220)
            }
            
            if settingsTab == 0 {
                generalSettingsContent
            } else {
                notificationsSettingsContent
            }
        }
    }
    
    private var notificationsSettingsContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show Android notifications")
                            .font(.system(size: 13, weight: .medium))
                        Text("Deliver phone notifications as macOS banners")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Toggle("", isOn: $model.showAndroidNotifications)
                        .labelsHidden()
                }
                .padding(14)
                
                Divider()
                    .overlay(glassBorderSubtle)
                
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show message previews")
                            .font(.system(size: 13, weight: .medium))
                        Text("Display text message previews in alert banners")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Toggle("", isOn: $model.showNotificationPreviews)
                        .labelsHidden()
                        .disabled(!model.showAndroidNotifications)
                }
                .padding(14)
            }
            .background(glassMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
            
            Button(action: {
                model.sendTestNotification()
            }) {
                HStack(spacing: 6) {
                    Image(systemName: "paperplane")
                        .font(.system(size: 12))
                    Text("Send Test Notification")
                        .font(.system(size: 12, weight: .medium))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(glassFill)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
    }
    
    private var generalSettingsContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Save Location Card
            VStack(alignment: .leading, spacing: 10) {
                Text("DOWNLOADS DESTINATION")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundColor(.secondary)
                
                HStack(spacing: 12) {
                    Image(systemName: "folder.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.accentColor)
                    
                    Text(model.downloadsDirectoryPath)
                        .font(.system(size: 13, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    
                    Spacer()
                    
                    Button("Change…") {
                        model.chooseDownloadsDirectory()
                    }
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(glassFill)
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
                    .buttonStyle(.plain)
                    
                    Button("Open") {
                        model.openDownloadsDirectory()
                    }
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(glassFill)
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(glassBorder, lineWidth: 1))
                    .buttonStyle(.plain)
                }
                .padding(14)
                .background(glassMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
            }
            
            // App & Connection Preferences
            VStack(alignment: .leading, spacing: 10) {
                Text("SYSTEM & ACTIONS")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundColor(.secondary)
                
                VStack(spacing: 0) {
                    HStack {
                        Text("Hide Bridge from Dock")
                            .font(.system(size: 13, weight: .medium))
                        Spacer()
                        Toggle("", isOn: $model.hideFromDock)
                            .labelsHidden()
                    }
                    .padding(14)
                    
                    Divider()
                        .overlay(glassBorderSubtle)
                    
                    HStack {
                        Text("Launch Bridge at Login")
                            .font(.system(size: 13, weight: .medium))
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { model.isOpenAtLogin },
                            set: { _ in model.toggleOpenAtLogin() }
                        ))
                        .labelsHidden()
                    }
                    .padding(14)
                }
                .background(glassMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
                
                HStack(spacing: 10) {
                    if model.isConnected {
                        Button(action: { model.disconnect() }) {
                            Label("Disconnect", systemImage: "xmark.circle")
                                .font(.system(size: 12, weight: .medium))
                        }
                    }
                    
                    Button(action: { model.reconnect() }) {
                        Label("Reconnect", systemImage: "arrow.clockwise")
                            .font(.system(size: 12, weight: .medium))
                    }
                    
                    Button(role: .destructive, action: { showClearConfirm = true }) {
                        Label("Reset Pairing", systemImage: "trash")
                            .font(.system(size: 12, weight: .medium))
                    }
                    
                    Spacer()
                    
                    Button("Pairing QR…") {
                        showPairingSheet = true
                    }
                    
                    Button("Diagnostics…") {
                        showDiagnosticsSheet = true
                    }
                }
                .font(.subheadline)
                .padding(.top, 4)
            }
        }
        .alert("Clear & Reset Pairing?", isPresented: $showClearConfirm) {
            Button("Reset", role: .destructive) {
                model.clearAndResetPairing()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will generate a new AES key and disconnect your phone. You will need to re-scan the QR code.")
        }
    }
    
    // MARK: - Diagnostics Sheet
    private var diagnosticsSheetView: some View {
        VStack(spacing: 16) {
            HStack {
                Text("Connection Diagnostics")
                    .font(.headline)
                Spacer()
                Button("Done") {
                    showDiagnosticsSheet = false
                }
                .keyboardShortcut(.defaultAction)
            }
            
            ScrollView {
                Text(LogManager.shared.getDiagnostics())
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(Color.black.opacity(0.4))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(glassBorderSubtle, lineWidth: 1))
            }
            .frame(height: 320)
            
            HStack {
                Button("Copy Diagnostics") {
                    model.copyDiagnostics()
                }
                Spacer()
            }
        }
        .padding(24)
        .frame(width: 560, height: 440)
        .background(glassMaterial)
    }
    
    private func formatByteCount(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }
}
