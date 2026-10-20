import SwiftUI
import AppKit
import UniformTypeIdentifiers

public struct MainWindowView: View {
    @ObservedObject var model: AppModel
    @State private var isDropTargeted: Bool = false
    @State private var showDiagnosticsSheet: Bool = false
    @State private var showPairingSheet: Bool = false
    @State private var showClearConfirm: Bool = false
    
    public init(model: AppModel) {
        self.model = model
    }
    
    public var body: some View {
        VStack(spacing: 0) {
            // Header Bar
            headerSection
                .padding(.horizontal, 24)
                .padding(.top, 20)
                .padding(.bottom, 16)
                .background(Color(NSColor.windowBackgroundColor))
            
            Divider()
            
            ScrollView {
                VStack(spacing: 20) {
                    // Drop Zone / Send Files
                    dropZoneCard
                    
                    // Active Transfer Progress
                    if model.isTransferring {
                        activeTransferBanner
                    }
                    
                    // Recent Transfers List
                    recentTransfersSection
                    
                    // Settings & Destination
                    settingsSection
                }
                .padding(24)
            }
        }
        .frame(minWidth: 620, idealWidth: 660, minHeight: 520, idealHeight: 600)
        .sheet(isPresented: $showPairingSheet) {
            PairingQRView(pairingJson: model.pairingJson, model: model)
        }
        .sheet(isPresented: $showDiagnosticsSheet) {
            diagnosticsSheetView
        }
    }
    
    // MARK: - Header
    private var headerSection: some View {
        HStack(spacing: 14) {
            if let logoUrl = Bundle.main.url(forResource: "BridgeLogo", withExtension: "png"),
               let image = NSImage(contentsOf: logoUrl) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 36, height: 36)
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text("Bridge")
                    .font(.title2)
                    .fontWeight(.semibold)
                Text("Native macOS & Android AirDrop & Clipboard")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            // Connection Status Badge
            HStack(spacing: 8) {
                Circle()
                    .fill(model.isConnected ? Color.green : Color.orange)
                    .frame(width: 9, height: 9)
                Text(model.isConnected ? "Connected" : "Waiting for Phone")
                    .font(.subheadline)
                    .fontWeight(.medium)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color(NSColor.controlBackgroundColor))
            .cornerRadius(20)
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
            )
        }
    }
    
    // MARK: - Drop Zone
    private var dropZoneCard: some View {
        VStack(spacing: 12) {
            Image(systemName: isDropTargeted ? "arrow.down.doc.fill" : "square.and.arrow.up.circle.fill")
                .resizable()
                .scaledToFit()
                .frame(width: 44, height: 44)
                .foregroundColor(isDropTargeted ? .accentColor : .secondary)
            
            Text("Send Files to Android")
                .font(.headline)
            
            Text("Drag & drop files here, or choose from your Mac")
                .font(.subheadline)
                .foregroundColor(.secondary)
            
            Button(action: {
                model.selectAndSendFiles()
            }) {
                Label("Choose Files…", systemImage: "doc.badge.plus")
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!model.isConnected)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(isDropTargeted ? Color.accentColor.opacity(0.08) : Color(NSColor.controlBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(
                    isDropTargeted ? Color.accentColor : Color.secondary.opacity(0.25),
                    style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                )
        )
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
                        self.model.sendFile(url: url)
                    }
                } else if let url = item as? URL {
                    DispatchQueue.main.async {
                        self.model.sendFile(url: url)
                    }
                }
            }
        }
    }
    
    // MARK: - Active Transfer Banner
    private var activeTransferBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(model.activeTransferName, systemImage: "arrow.triangle.2.circlepath")
                    .font(.subheadline)
                    .fontWeight(.medium)
                Spacer()
                Text("\(Int(model.activeTransferProgress * 100))%")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            ProgressView(value: model.activeTransferProgress)
                .progressViewStyle(.linear)
        }
        .padding(14)
        .background(Color(NSColor.controlBackgroundColor))
        .cornerRadius(10)
    }
    
    // MARK: - Recent Transfers Section
    private var recentTransfersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Recent Transfers")
                .font(.headline)
            
            if model.recentTransfers.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 6) {
                        Image(systemName: "tray")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text("No file transfers yet.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 20)
                    Spacer()
                }
                .background(Color(NSColor.controlBackgroundColor).opacity(0.5))
                .cornerRadius(10)
            } else {
                VStack(spacing: 8) {
                    ForEach(model.recentTransfers.prefix(6)) { item in
                        transferRow(item: item)
                    }
                }
            }
        }
    }
    
    private func transferRow(item: FileTransferItem) -> some View {
        HStack(spacing: 12) {
            Image(systemName: item.isIncoming ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                .font(.title3)
                .foregroundColor(item.isIncoming ? .green : .blue)
            
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)
                
                HStack(spacing: 8) {
                    Text(item.status)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if item.size > 0 {
                        Text("•")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(formatByteCount(item.size))
                            .font(.caption)
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
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        .padding(10)
        .background(Color(NSColor.controlBackgroundColor))
        .cornerRadius(8)
    }
    
    // MARK: - Settings Section
    private var settingsSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Settings")
                .font(.headline)
            
            // Save Location
            VStack(alignment: .leading, spacing: 8) {
                Text("Save Received Files To:")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundColor(.accentColor)
                    Text(model.downloadsDirectoryPath)
                        .font(.subheadline)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    
                    Spacer()
                    
                    Button("Change…") {
                        model.chooseDownloadsDirectory()
                    }
                    .controlSize(.small)
                    
                    Button("Open Folder") {
                        model.openDownloadsDirectory()
                    }
                    .controlSize(.small)
                }
                .padding(10)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(8)
            }
            
            Divider()
            
            // System & Connection Preferences
            VStack(alignment: .leading, spacing: 12) {
                Text("App & Connection")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                HStack(spacing: 24) {
                    Toggle("Hide Bridge from Dock", isOn: $model.hideFromDock)
                    
                    Toggle("Launch Bridge at Login", isOn: Binding(
                        get: { model.isOpenAtLogin },
                        set: { _ in model.toggleOpenAtLogin() }
                    ))
                    
                    Spacer()
                }
                .font(.subheadline)
                
                HStack(spacing: 10) {
                    if model.isConnected {
                        Button(action: { model.disconnect() }) {
                            Label("Disconnect", systemImage: "xmark.circle")
                        }
                    }
                    
                    Button(action: { model.reconnect() }) {
                        Label("Reconnect", systemImage: "arrow.clockwise")
                    }
                    
                    Button(role: .destructive, action: { showClearConfirm = true }) {
                        Label("Clear Pairing", systemImage: "trash")
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
                    .padding(12)
                    .background(Color(NSColor.textBackgroundColor))
                    .cornerRadius(8)
            }
            .frame(height: 320)
            
            HStack {
                Button("Copy Diagnostics") {
                    model.copyDiagnostics()
                }
                Spacer()
            }
        }
        .padding(20)
        .frame(width: 540, height: 420)
    }
    
    private func formatByteCount(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }
}
