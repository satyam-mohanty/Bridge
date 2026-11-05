import SwiftUI
import AppKit
import UniformTypeIdentifiers
import CoreImage.CIFilterBuiltins

public enum BridgePage: String, CaseIterable, Identifiable {
    case overview = "Overview"
    case transfers = "Transfers"
    case clipboard = "Clipboard"
    case notifications = "Notifications"
    case devices = "Devices"
    case preferences = "Preferences"
    case pairDevice = "Pair a device"

    public var id: String { rawValue }
    public var icon: String {
        switch self {
        case .overview: "rectangle.grid.1x2"
        case .transfers: "arrow.left.arrow.right"
        case .clipboard: "doc.on.clipboard"
        case .notifications: "bell"
        case .devices: "iphone"
        case .preferences: "slider.horizontal.3"
        case .pairDevice: "qrcode"
        }
    }
}

// MARK: - Native Apple macOS Visual Effect Vibrancy
public struct VisualEffectBackground: NSViewRepresentable {
    public let material: NSVisualEffectView.Material
    public let blendingMode: NSVisualEffectView.BlendingMode
    public let state: NSVisualEffectView.State

    public init(
        material: NSVisualEffectView.Material = .underWindowBackground,
        blendingMode: NSVisualEffectView.BlendingMode = .behindWindow,
        state: NSVisualEffectView.State = .active
    ) {
        self.material = material
        self.blendingMode = blendingMode
        self.state = state
    }

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = state
        view.autoresizingMask = [.width, .height]
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
        nsView.state = state
    }
}

public struct BridgePalette {
    public let dark: Bool

    // True dark / near-black tones for dark mode, crisp clean light tones for light mode
    public var canvas: Color {
        dark ? Color(red: 0.06, green: 0.06, blue: 0.07) : Color(red: 250/255, green: 249/255, blue: 247/255) // #0F0F12
    }
    public var sidebar: Color {
        dark ? Color(red: 0.035, green: 0.035, blue: 0.04) : Color(red: 238/255, green: 237/255, blue: 233/255) // #09090A deep black
    }
    public var surface: Color {
        dark ? Color(red: 0.09, green: 0.09, blue: 0.10) : .white // #17171A
    }
    public var secondarySurface: Color {
        dark ? Color(red: 0.14, green: 0.14, blue: 0.15) : Color(red: 244/255, green: 243/255, blue: 240/255) // #242426
    }
    public var border: Color {
        dark ? Color.white.opacity(0.08) : Color(red: 227/255, green: 227/255, blue: 222/255) // #E3E3DE
    }
    public var text: Color {
        dark ? Color(white: 0.96) : Color(red: 41/255, green: 43/255, blue: 46/255) // #F5F5F5
    }
    public var muted: Color {
        dark ? Color(white: 0.52) : Color(red: 142/255, green: 144/255, blue: 148/255) // #8E9094
    }
    public var accent: Color {
        dark ? Color.white : Color(red: 35/255, green: 35/255, blue: 38/255) // Zero blue! Monochrome high-contrast in dark
    }
    public var buttonText: Color {
        dark ? Color.black : Color.white
    }
    public var linkAccent: Color {
        dark ? Color(white: 0.90) : Color(red: 40/255, green: 40/255, blue: 44/255)
    }
    public var selected: Color {
        dark ? Color.white.opacity(0.12) : Color.black.opacity(0.06) // Sleek neutral active pill, zero blue
    }
    public var selectedText: Color {
        dark ? Color.white : Color(red: 20/255, green: 20/255, blue: 22/255)
    }
    public var successBannerBg: Color {
        dark ? Color(red: 0.08, green: 0.15, blue: 0.10) : Color(red: 237/255, green: 243/255, blue: 238/255) // #EDF3EE
    }
    public var successBannerBorder: Color {
        dark ? Color.white.opacity(0.08) : Color(red: 224/255, green: 233/255, blue: 225/255) // #E0E9E1
    }
    public var success: Color {
        dark ? Color(red: 0.38, green: 0.82, blue: 0.52) : Color(red: 45/255, green: 119/255, blue: 71/255) // #2D7747
    }
    public var dropZoneBorder: Color {
        dark ? Color.white.opacity(0.16) : Color(red: 215/255, green: 215/255, blue: 218/255)
    }
    public var dropZoneBg: Color {
        dark ? Color(red: 0.08, green: 0.08, blue: 0.09) : Color(red: 248/255, green: 248/255, blue: 250/255)
    }
    public var toggleTint: Color {
        success
    }
}

public struct BridgeMark: View {
    public var color: Color? = nil

    public init(color: Color? = nil) {
        self.color = color
    }

    public var body: some View {
        Group {
            if let image = NSImage(named: "BridgeLogo") {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            } else if let logoUrl = Bundle.main.url(forResource: "BridgeLogo", withExtension: "png"),
                      let image = NSImage(contentsOf: logoUrl) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(Color(red: 41/255, green: 43/255, blue: 46/255))
                    Image(systemName: "bridge")
                        .font(.system(size: 14))
                        .foregroundStyle(.white)
                }
            }
        }
    }
}

public struct PixelPhoneGraphic: View {
    public let width: CGFloat
    public let height: CGFloat
    public let showDate: Bool
    public let cornerRadius: CGFloat

    public init(width: CGFloat = 88, height: CGFloat = 160, showDate: Bool = false, cornerRadius: CGFloat = 18) {
        self.width = width
        self.height = height
        self.showDate = showDate
        self.cornerRadius = cornerRadius
    }

    public var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color(red: 236/255, green: 235/255, blue: 231/255))
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(Color(red: 41/255, green: 43/255, blue: 46/255), lineWidth: max(2.5, width * 0.035))

            VStack(spacing: showDate ? 6 : 4) {
                // Punch hole camera
                Circle()
                    .fill(Color(red: 41/255, green: 43/255, blue: 46/255))
                    .frame(width: max(3.5, width * 0.05), height: max(3.5, width * 0.05))
                    .padding(.top, max(6, height * 0.06))

                Spacer()

                // Lock screen time
                Text(Date.now.formatted(date: .omitted, time: .shortened))
                    .font(.system(size: max(12, width * 0.22), weight: .medium, design: .default))
                    .foregroundStyle(Color(red: 64/255, green: 66/255, blue: 69/255))

                if showDate {
                    Text(Date.now.formatted(date: .complete, time: .omitted))
                        .font(.system(size: max(7.5, width * 0.085), weight: .regular))
                        .foregroundStyle(Color(red: 142/255, green: 144/255, blue: 148/255))
                        .lineLimit(1)
                }

                Spacer()

                // Home indicator
                Capsule()
                    .fill(Color(red: 142/255, green: 144/255, blue: 148/255))
                    .frame(width: max(20, width * 0.35), height: max(2, height * 0.018))
                    .padding(.bottom, max(6, height * 0.06))
            }
        }
        .frame(width: width, height: height)
    }
}

public struct MainWindowView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var updateManager = UpdateManager.shared
    @Environment(\.colorScheme) private var colorScheme
    @State private var page: BridgePage = .overview
    @State private var isDropTargeted = false
    @State private var showDiagnosticsSheet = false
    @State private var pageBeforePairing: BridgePage = .devices
    @State private var showClearConfirm = false
    @State private var showForgetConfirm = false
    @State private var preferencesTab = 0
    @State private var transferFilter = 0
    @State private var clipboardText = NSPasteboard.general.string(forType: .string) ?? ""
    @State private var copiedSnippetId: String? = nil
    @State private var pairingCodeCopied = false

    private var palette: BridgePalette {
        let followsDarkSystem = colorScheme == .dark
        return BridgePalette(dark: model.appearanceMode == "dark" || (model.appearanceMode == "auto" && followsDarkSystem))
    }

    private var deviceName: String {
        model.connectedDeviceName
    }

    private var batteryText: String? {
        if let level = model.peerBatteryLevel {
            return "\(level)%"
        }
        return nil
    }

    private var deviceSubtitle: String {
        if model.isConnected {
            if let batt = batteryText {
                return "Connected · \(batt)"
            }
            return "Connected"
        }
        return model.hasPairedDevice ? "Offline" : "Not paired"
    }

    private var windowScheme: ColorScheme? {
        switch model.appearanceMode {
        case "light": .light
        case "dark": .dark
        default: nil
        }
    }

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        HStack(spacing: 0) {
            sidebar

            VStack(spacing: 0) {
                toolbar
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 22) {
                        pageContent
                    }
                    .padding(.horizontal, 36)
                    .padding(.top, 18)
                    .padding(.bottom, 36)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(palette.canvas)
        }
        .frame(minWidth: 920, minHeight: 650)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WindowConfigurator())
        .background(palette.canvas)
        .foregroundStyle(palette.text)
        .preferredColorScheme(windowScheme)
        .tint(palette.accent)
        .sheet(isPresented: $showDiagnosticsSheet) {
            diagnosticsSheetView
        }
        .sheet(isPresented: $model.showOnboarding) {
            MacOnboardingView(model: model)
        }
        .alert("Reset pairing?", isPresented: $showClearConfirm) {
            Button("Reset Pairing", role: .destructive) { model.clearAndResetPairing() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This disconnects your phone and creates a new pairing key. You will need to pair the device again.")
        }
        .alert("Forget this device?", isPresented: $showForgetConfirm) {
            Button("Forget Device", role: .destructive) { model.clearAndResetPairing() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the trusted connection. You will need to pair your phone again.")
        }
        .onChange(of: page) { nextPage in
            if nextPage == .clipboard {
                clipboardText = NSPasteboard.general.string(forType: .string) ?? ""
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .openAboutPage)) { _ in
            page = .preferences
            preferencesTab = 2
        }
    }

    // MARK: - Sidebar
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Space for macOS traffic light buttons with proper breathing room
            Color.clear
                .frame(height: 38)
                .background(WindowDragArea())

            // Brand Header: Official Logo + Title
            HStack(spacing: 10) {
                BridgeMark()
                    .frame(width: 28, height: 28)
                    .shadow(color: Color.black.opacity(palette.dark ? 0.35 : 0.12), radius: 4, x: 0, y: 2)

                VStack(alignment: .leading, spacing: 1) {
                    Text("Bridge")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.text)
                    Text("Device Sync")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(palette.muted)
                }
            }
            .allowsHitTesting(false)
            .padding(.leading, 18)
            .padding(.bottom, 16)
            .background(WindowDragArea())

            // Section Label
            Text("CONNECTIVITY")
                .font(.system(size: 10, weight: .bold))
                .tracking(0.6)
                .foregroundStyle(palette.muted)
                .padding(.horizontal, 16)
                .padding(.bottom, 6)

            // Primary Navigation Items
            VStack(spacing: 3) {
                sidebarNavItem(.overview, title: "Overview")
                sidebarNavItem(.transfers, title: "Transfers", badgeCount: model.recentTransfers.isEmpty ? nil : model.recentTransfers.count)
                sidebarNavItem(.clipboard, title: "Clipboard")
                sidebarNavItem(.notifications, title: "Notifications", badgeCount: unreadNotificationsCount > 0 ? unreadNotificationsCount : nil)
                sidebarNavItem(.devices, title: "Devices")
            }
            .padding(.horizontal, 10)

            Spacer(minLength: 12)

            // SUPPORT & SYSTEM Section
            VStack(alignment: .leading, spacing: 8) {
                Text("SUPPORT")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(palette.muted)
                    .padding(.horizontal, 12)

                // Buy me a coffee Button
                Link(destination: URL(string: "https://buymeacoffee.com/satyammohanty")!) {
                    HStack(spacing: 9) {
                        Image(systemName: "cup.and.saucer.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.orange)
                        Text("Buy me a coffee")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(palette.text)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 11)
                    .frame(height: 34)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(palette.dark ? 0.18 : 0.60), Color.clear],
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 0.75
                            )
                    )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)

                // Active Device Card
                Button {
                    page = .devices
                } label: {
                    HStack(spacing: 9) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(model.isConnected ? Color.blue.opacity(0.2) : Color.gray.opacity(0.15))
                            Image(systemName: model.hasPairedDevice ? "iphone" : "iphone.slash")
                                .font(.system(size: 14))
                                .foregroundStyle(model.isConnected ? palette.accent : palette.muted)
                        }
                        .frame(width: 26, height: 26)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.hasPairedDevice ? deviceName : "No device paired")
                                .font(.system(size: 11.5, weight: .medium))
                                .foregroundStyle(palette.text)
                                .lineLimit(1)

                            HStack(spacing: 4) {
                                Circle()
                                    .fill(model.isConnected ? palette.success : palette.muted)
                                    .frame(width: 5, height: 5)
                                    .shadow(color: (model.isConnected ? palette.success : Color.clear).opacity(0.5), radius: 2)
                                Text(deviceSubtitle)
                                    .font(.system(size: 10))
                                    .foregroundStyle(model.isConnected ? palette.success : palette.muted)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(palette.dark ? 0.18 : 0.60), Color.clear],
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 0.75
                            )
                    )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.top, 4)

                // Preferences Link
                Button {
                    page = .preferences
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 13))
                            .frame(width: 18)
                        Text("Preferences")
                            .font(.system(size: 13, weight: page == .preferences ? .semibold : .regular))
                    }
                    .foregroundStyle(page == .preferences ? palette.selectedText : palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .frame(height: 34)
                    .background(
                        Group {
                            if page == .preferences {
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(palette.selected)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .strokeBorder(
                                                LinearGradient(
                                                    colors: [Color.white.opacity(palette.dark ? 0.20 : 0.60), Color.clear],
                                                    startPoint: .top,
                                                    endPoint: .bottom
                                                ),
                                                lineWidth: 0.75
                                            )
                                    )
                            } else {
                                Color.clear
                            }
                        }
                    )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.top, 4)
                .padding(.bottom, 14)
            }
        }
        .frame(width: 215)
        .background(palette.sidebar)
        .overlay(alignment: .trailing) {
            Rectangle()
                .fill(palette.border)
                .frame(width: 0.5)
        }
    }

    private func sidebarNavItem(_ target: BridgePage, title: String, badgeCount: Int? = nil) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.12)) {
                page = target
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: target.icon)
                    .font(.system(size: 13.5, weight: page == target ? .semibold : .medium))
                    .frame(width: 20)
                    .foregroundStyle(page == target ? (palette.dark ? Color.white : palette.accent) : palette.muted)

                Text(title)
                    .font(.system(size: 13, weight: page == target ? .semibold : .regular))
                    .foregroundStyle(page == target ? (palette.dark ? Color.white : palette.text) : palette.text)

                Spacer(minLength: 0)

                if let count = badgeCount {
                    Text("\(count)")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(page == target ? (palette.dark ? Color.white : palette.accent) : palette.muted)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(
                            Capsule()
                                .fill(page == target ? Color.white.opacity(0.2) : palette.secondarySurface)
                        )
                }
            }
            .foregroundStyle(page == target ? palette.selectedText : palette.text)
            .padding(.horizontal, 10)
            .frame(height: 34)
            .background(
                Group {
                    if page == target {
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(palette.selected)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .strokeBorder(
                                        LinearGradient(
                                            colors: [Color.white.opacity(palette.dark ? 0.20 : 0.60), Color.clear],
                                            startPoint: .top,
                                            endPoint: .bottom
                                        ),
                                        lineWidth: 0.75
                                    )
                            )
                            .shadow(color: Color.black.opacity(palette.dark ? 0.25 : 0.04), radius: 4, x: 0, y: 1.5)
                    } else {
                        Color.clear
                    }
                }
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var unreadNotificationsCount: Int {
        model.recentNotifications.filter { $0.isUnread }.count
    }

    // MARK: - Toolbar / Top Bar
    private var toolbar: some View {
        HStack {
            Text(toolbarTitle)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(palette.text)

            Spacer()

            HStack(spacing: 7) {
                Circle()
                    .fill(statusIndicatorColor)
                    .frame(width: 7, height: 7)
                    .shadow(color: statusIndicatorColor.opacity(model.isConnected ? 0.6 : 0.2), radius: 3)

                Text(statusIndicatorText)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.muted)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(palette.dark ? 0.20 : 0.60), Color.clear],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 0.75
                    )
            )
            .shadow(color: Color.black.opacity(palette.dark ? 0.20 : 0.04), radius: 4, x: 0, y: 1)
        }
        .allowsHitTesting(false)
        .padding(.horizontal, 28)
        .frame(height: 52)
        .background(WindowDragArea())
    }

    private var toolbarTitle: String {
        if page == .pairDevice {
            return "Devices / Pair a device"
        }
        return page.rawValue
    }

    private var statusIndicatorColor: Color {
        if page == .pairDevice {
            return palette.muted
        }
        return model.isConnected ? palette.success : palette.muted
    }

    private var statusIndicatorText: String {
        if page == .pairDevice {
            return "Discoverable on this Mac"
        }
        if model.isConnected {
            return "\(deviceName) · Connected"
        }
        return model.hasPairedDevice ? "\(deviceName) · Offline" : "No device paired"
    }

    // MARK: - Status Bar / Bottom Bar
    private var statusBar: some View {
        HStack {
            HStack(spacing: 7) {
                Image(systemName: statusBarIcon)
                    .font(.system(size: 11))
                Text(statusBarText)
                    .font(.system(size: 11))
            }
            .foregroundStyle(palette.muted)

            Spacer()

            Text(Host.current().localizedName ?? "Satyam’s MacBook Pro")
                .font(.system(size: 11))
                .foregroundStyle(palette.muted)
        }
        .allowsHitTesting(false)
        .padding(.horizontal, 28)
        .frame(height: 32)
        .background(WindowDragArea())
        .overlay(alignment: .top) {
            Rectangle()
                .fill(palette.border)
                .frame(height: 1)
        }
    }

    private var statusBarIcon: String {
        if page == .pairDevice {
            return "wifi"
        }
        return model.isConnected ? "lock.fill" : "wifi.slash"
    }

    private var statusBarText: String {
        if page == .pairDevice {
            return "Listening for Android devices"
        }
        return model.isConnected ? "Local connection · End-to-end encrypted" : "Waiting for \(deviceName)"
    }

    // MARK: - Page Content Switcher
    @ViewBuilder
    private var pageContent: some View {
        switch page {
        case .overview:
            overviewPage
        case .transfers:
            transfersPage
        case .clipboard:
            clipboardPage
        case .notifications:
            notificationsPage
        case .devices:
            devicesPage
        case .preferences:
            preferencesPage
        case .pairDevice:
            pairDevicePage
        }
    }

    // MARK: - 1. OVERVIEW PAGE
    private var overviewPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            if model.isConnected {
                pageHeading("Connected to your \(deviceName)", subtitle: "Your Android and Mac, working together.")

                // Connected Device Card
                connectedDeviceCard

                // Drag & Drop Upload Zone
                sendFilesCard

                if model.isTransferring {
                    activeTransferCard
                }

                // Two Columns: Latest Clipboard & Recent Activity
                HStack(alignment: .top, spacing: 20) {
                    overviewClipboardCard
                        .frame(maxWidth: .infinity)

                    overviewRecentActivityCard
                        .frame(maxWidth: .infinity)
                }
            } else if model.hasPairedDevice {
                pageHeading("Waiting for \(deviceName)", subtitle: "Bridge will reconnect as soon as your devices can find each other.")

                // Disconnected Main Card
                disconnectedMainCard

                // Two Columns: A quick connection check & Until you're reconnected
                HStack(alignment: .top, spacing: 20) {
                    connectionCheckCard
                        .frame(maxWidth: .infinity)

                    pausedServicesCard
                        .frame(maxWidth: .infinity)
                }

                // Bottom Diagnostics Helper Bar
                HStack {
                    Text("Still not connecting? Diagnostics can help find the issue.")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)

                    Spacer()

                    Button("Open Diagnostics…") {
                        showDiagnosticsSheet = true
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 30)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .padding(.top, 4)
            } else {
                pageHeading("Get started with Bridge", subtitle: "Pair your Android phone to transfer files, sync clipboard, and receive notifications.")
                noPairedDevicesCard
            }
        }
    }

    private func pageHeading(_ title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(palette.text)

            Text(subtitle)
                .font(.system(size: 13))
                .foregroundStyle(palette.muted)
        }
        .padding(.bottom, 2)
    }

    private var connectedDeviceCard: some View {
        HStack(spacing: 16) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [
                                Color(red: 0, green: 122/255, blue: 1).opacity(0.18),
                                Color(red: 0, green: 122/255, blue: 1).opacity(0.06)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(Color(red: 0, green: 122/255, blue: 1).opacity(0.25), lineWidth: 1)
                    )
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 24, weight: .regular))
                    .foregroundStyle(Color(red: 0, green: 122/255, blue: 1))
            }
            .frame(width: 52, height: 52)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(deviceName)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(palette.text)

                    HStack(spacing: 5) {
                        Circle()
                            .fill(Color(red: 52/255, green: 199/255, blue: 89/255))
                            .frame(width: 6, height: 6)
                            .shadow(color: Color(red: 52/255, green: 199/255, blue: 89/255).opacity(0.6), radius: 3)
                        Text("Connected")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color(red: 52/255, green: 199/255, blue: 89/255))
                    }
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3.5)
                    .background(
                        Capsule()
                            .fill(palette.successBannerBg)
                    )
                    .overlay(
                        Capsule()
                            .strokeBorder(palette.successBannerBorder, lineWidth: 1)
                    )
                }

                HStack(spacing: 14) {
                    HStack(spacing: 4) {
                        Image(systemName: "wifi")
                            .font(.system(size: 11))
                        Text(model.networkName)
                    }
                    if let batt = batteryText {
                        HStack(spacing: 4) {
                            Image(systemName: model.isPeerCharging ? "battery.100percent.bolt" : "battery.100percent")
                                .font(.system(size: 12))
                            Text(batt)
                        }
                    }
                }
                .font(.system(size: 12))
                .foregroundStyle(palette.muted)
            }

            Spacer()

            Button("Manage Device") {
                page = .devices
            }
            .buttonStyle(.plain)
            .font(.system(size: 12.5, weight: .medium))
            .padding(.horizontal, 14)
            .frame(height: 30)
            .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(palette.border, lineWidth: 1)
            )
            .foregroundStyle(palette.text)
        }
        .padding(18)
        .bridgeCard(palette)
    }

    private var sendFilesCard: some View {
        HStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: isDropTargeted ? [
                                Color(red: 0, green: 122/255, blue: 1).opacity(0.35),
                                Color(red: 0, green: 122/255, blue: 1).opacity(0.15)
                            ] : [
                                palette.secondarySurface,
                                palette.secondarySurface.opacity(0.6)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 52, height: 52)
                    .overlay(
                        Circle()
                            .strokeBorder(isDropTargeted ? Color(red: 0, green: 122/255, blue: 1).opacity(0.6) : palette.border, lineWidth: 1)
                    )

                Image(systemName: isDropTargeted ? "airdrop" : "arrow.up.circle")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(isDropTargeted ? Color(red: 0, green: 122/255, blue: 1) : palette.text)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(isDropTargeted ? "Drop files to beam to your phone" : "Drop files to send to your phone")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.text)

                Text("Photos, documents, or folders. Sent directly peer-to-peer at full Wi-Fi speed.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(palette.muted)
            }

            Spacer(minLength: 0)

            Button("Select Files…") {
                model.selectAndSendFiles()
            }
            .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(isDropTargeted ? Color(red: 0, green: 122/255, blue: 1).opacity(0.08) : palette.dropZoneBg)
        )
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(
                    isDropTargeted ? Color(red: 0, green: 122/255, blue: 1) : palette.dropZoneBorder,
                    style: StrokeStyle(lineWidth: isDropTargeted ? 2 : 1, dash: [7, 5])
                )
        )
        .shadow(
            color: isDropTargeted ? Color(red: 0, green: 122/255, blue: 1).opacity(0.25) : Color.black.opacity(palette.dark ? 0.20 : 0.04),
            radius: isDropTargeted ? 12 : 6,
            x: 0,
            y: 3
        )
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDroppedFiles(providers: providers)
            return true
        }
    }

    private var overviewClipboardCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Latest clipboard")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(palette.text)

                Spacer()

                Button("View all") {
                    page = .clipboard
                }
                .buttonStyle(.plain)
                .font(.system(size: 12.5))
                .foregroundStyle(palette.linkAccent)
            }

            VStack(alignment: .leading, spacing: 12) {
                let latestSnippet = model.recentClipboardSnippets.first
                let currentClip = NSPasteboard.general.string(forType: .string) ?? ""
                let displayText = latestSnippet?.text ?? (currentClip.isEmpty ? "Copy text on either device to sync it here." : currentClip)

                HStack {
                    Text(latestSnippet != nil ? "\(latestSnippet!.origin) · \(latestSnippet!.date.formatted(date: .omitted, time: .shortened))" : "Current Mac Clipboard")
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)

                    Spacer()

                    Button {
                        if let first = latestSnippet {
                            model.copySnippet(first.text)
                        } else {
                            copyCurrentClipboard()
                        }
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.muted)
                    }
                    .buttonStyle(.plain)
                }

                Text(displayText)
                    .font(.system(size: 13))
                    .foregroundStyle(palette.text)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 5) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(palette.success)
                    Text("Ready to paste on this Mac")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 128, alignment: .topLeading)
            .bridgeCard(palette)
        }
    }

    private var overviewRecentActivityCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Recent activity")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(palette.text)

                Spacer()

                Button("View transfers") {
                    page = .transfers
                }
                .buttonStyle(.plain)
                .font(.system(size: 12.5))
                .foregroundStyle(palette.linkAccent)
            }

            VStack(spacing: 12) {
                if model.recentTransfers.isEmpty && model.recentNotifications.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "clock")
                            .font(.system(size: 20))
                            .foregroundStyle(palette.muted.opacity(0.7))
                        Text("No recent activity yet")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 88)
                } else {
                    ForEach(model.recentTransfers.prefix(2)) { transfer in
                        activityRow(
                            icon: transfer.isIncoming ? "arrow.down.left" : "arrow.up.right",
                            title: transfer.name,
                            detail: "\(transfer.status) · \(formatByteCount(transfer.size))",
                            timeText: transfer.date.formatted(date: .omitted, time: .shortened)
                        )
                    }
                    ForEach(model.recentNotifications.prefix(max(0, 3 - model.recentTransfers.count))) { notif in
                        activityRow(
                            icon: "bell",
                            title: notif.app,
                            detail: notif.title.isEmpty ? notif.body : notif.title,
                            timeText: notif.date.formatted(date: .omitted, time: .shortened)
                        )
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 128, alignment: .topLeading)
            .bridgeCard(palette)
        }
    }

    private func activityRow(icon: String, title: String, detail: String, timeText: String) -> some View {
        HStack(spacing: 11) {
            Image(systemName: icon)
                .font(.system(size: 12.5))
                .foregroundStyle(palette.muted)
                .frame(width: 16)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)

                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(palette.muted)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            Text(timeText)
                .font(.system(size: 11))
                .foregroundStyle(palette.muted)
        }
    }

    // MARK: - Overview Waiting State
    private var disconnectedMainCard: some View {
        HStack(spacing: 28) {
            PixelPhoneGraphic(width: 90, height: 164, showDate: true)

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text(deviceName)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.text)

                    HStack(spacing: 4) {
                        Circle()
                            .fill(palette.muted)
                            .frame(width: 5, height: 5)
                        Text("Offline")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(palette.muted)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                }

                Text("Your pairing is saved — there’s no need to set it up again.")
                    .font(.system(size: 12))
                    .foregroundStyle(palette.muted)

                HStack(spacing: 16) {
                    Button {
                        model.reconnect()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 12, weight: .semibold))
                            Text("Reconnect")
                                .font(.system(size: 12.5, weight: .medium))
                        }
                    }
                    .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)

                    Button("Pair another device…") {
                        openPairing(from: .overview)
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(palette.linkAccent)
                }
                .padding(.top, 4)

                HStack(spacing: 6) {
                    Image(systemName: "rays")
                        .font(.system(size: 11))
                    Text("Looking for your device on the local network…")
                        .font(.system(size: 11))
                }
                .foregroundStyle(palette.muted)
                .padding(.top, 2)
            }

            Spacer(minLength: 0)
        }
        .padding(24)
        .bridgeCard(palette)
    }

    private var connectionCheckCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("A quick connection check")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(alignment: .leading, spacing: 16) {
                recoveryStep("wifi", "Connect both devices to the same Wi-Fi.")
                recoveryStep("iphone", "Open Bridge on your Android.")
                recoveryStep("checkmark.shield", "Allow local network access on your Mac.")
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 160, alignment: .topLeading)
            .bridgeCard(palette)
        }
    }

    private var pausedServicesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Until you’re reconnected")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(alignment: .leading, spacing: 16) {
                recoveryStep("folder.badge.clock", "File transfers", subtitle: "Waiting for your device")
                recoveryStep("doc.on.clipboard", "Clipboard sync", subtitle: "Paused — nothing new will sync")
                recoveryStep("bell", "Notifications", subtitle: "Will resume when connected")
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 160, alignment: .topLeading)
            .bridgeCard(palette)
        }
    }

    private func recoveryStep(_ icon: String, _ title: String, subtitle: String? = nil) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 13))
                .foregroundStyle(palette.muted)
                .frame(width: 16)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: subtitle == nil ? .regular : .medium))
                    .foregroundStyle(palette.text)

                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)
                }
            }
        }
    }

    // MARK: - 2. TRANSFERS PAGE
    private var transfersPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center) {
                pageHeading("File transfers", subtitle: model.isConnected ? "Send and receive directly with \(deviceName)." : "Connect your phone to send and receive files.")
                Spacer()
                Button {
                    model.selectAndSendFiles()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "plus")
                            .font(.system(size: 11, weight: .semibold))
                        Text("Send Files…")
                    }
                }
                .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)
            }

            // In-Progress Transfer Card (Only shown during active transfer)
            if model.isTransferring {
                activeTransferCard
            }

            // Segmented Filter Tabs & Clear History
            HStack {
                HStack(spacing: 2) {
                    filterTabButton("All transfers", tag: 0)
                    filterTabButton("Sent", tag: 1)
                    filterTabButton("Received", tag: 2)
                }
                .padding(3)
                .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                Spacer()

                if !model.recentTransfers.isEmpty {
                    Button("Clear history") {
                        model.clearRecentTransfers()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(palette.muted)
                }
            }

            // Transfers Table Card
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("File")
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Direction")
                        .frame(width: 170, alignment: .leading)
                    Text("Date")
                        .frame(width: 130, alignment: .trailing)
                }
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(palette.muted)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                Divider()
                    .overlay(palette.border)

                if filteredTransfers.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "arrow.left.arrow.right")
                            .font(.system(size: 24))
                            .foregroundStyle(palette.muted.opacity(0.7))
                        Text("No file transfers yet")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Drop files or click Send Files to send directly to your phone.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(40)
                } else {
                    ForEach(filteredTransfers) { item in
                        transferTableRow(item)
                    }
                }
            }
            .bridgeCard(palette)

            // Bottom Saved Location Note
            HStack(spacing: 6) {
                Image(systemName: "folder")
                    .font(.system(size: 12))
                    .foregroundStyle(palette.muted)
                Text("Received files are saved to \(model.downloadsDirectoryPath)")
                    .font(.system(size: 11.5))
                    .foregroundStyle(palette.muted)
                Button("Open folder ↗") {
                    model.openDownloadsDirectory()
                }
                .buttonStyle(.plain)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(palette.linkAccent)
                Spacer()
            }
            .padding(.top, 2)
        }
    }

    private var activeTransferCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(palette.secondarySurface)
                    Image(systemName: "arrow.up.doc.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(palette.accent)
                }
                .frame(width: 42, height: 42)

                VStack(alignment: .leading, spacing: 3) {
                    Text(model.activeTransferName)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(palette.text)
                        .lineLimit(1)

                    Text("Transferring · \(Int(model.activeTransferProgress * 100))%")
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)
                }

                Spacer()

                Button("Cancel") {
                    model.cancelActiveTransfer()
                }
                .buttonStyle(.plain)
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(palette.linkAccent)
            }

            ProgressView(value: model.activeTransferProgress)
                .tint(palette.accent)

            HStack {
                Text("\(Int(model.activeTransferProgress * 100))%")
                    .font(.system(size: 11))
                    .foregroundStyle(palette.muted)
                Spacer()
            }
        }
        .padding(18)
        .bridgeCard(palette)
    }

    private func filterTabButton(_ label: String, tag: Int) -> some View {
        Button {
            transferFilter = tag
        } label: {
            Text(label)
                .font(.system(size: 12, weight: transferFilter == tag ? .medium : .regular))
                .foregroundStyle(transferFilter == tag ? palette.text : palette.muted)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(
                    transferFilter == tag ? palette.surface : Color.clear,
                    in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                )
                .shadow(color: transferFilter == tag ? Color.black.opacity(0.04) : .clear, radius: 2, y: 1)
        }
        .buttonStyle(.plain)
    }

    private var filteredTransfers: [FileTransferItem] {
        switch transferFilter {
        case 1:
            return model.recentTransfers.filter { !$0.isIncoming }
        case 2:
            return model.recentTransfers.filter(\.isIncoming)
        default:
            return model.recentTransfers
        }
    }

    private func transferTableRow(_ item: FileTransferItem) -> some View {
        HStack(spacing: 12) {
            Image(systemName: fileSymbol(for: item.name))
                .font(.system(size: 15))
                .foregroundStyle(palette.muted)
                .frame(width: 20)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(palette.text)
                    .lineLimit(1)

                Text("\(formatByteCount(item.size)) · \(fileType(for: item.name))")
                    .font(.system(size: 10.5))
                    .foregroundStyle(palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 4) {
                Image(systemName: item.isIncoming ? "arrow.down.left" : "arrow.up.right")
                    .font(.system(size: 10))
                Text(item.isIncoming ? "Received from \(deviceName)" : "Sent to \(deviceName)")
                    .font(.system(size: 11.5))
            }
            .foregroundStyle(palette.muted)
            .frame(width: 170, alignment: .leading)

            Text(transferDate(item.date))
                .font(.system(size: 11))
                .foregroundStyle(palette.muted)
                .frame(width: 130, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(palette.border.opacity(0.6))
                .frame(height: 1)
        }
    }

    private func fileSymbol(for name: String) -> String {
        let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "gif", "webp": return "photo"
        case "pdf": return "doc.text"
        case "zip", "7z", "rar": return "folder"
        case "mp4", "mov", "m4v": return "film"
        case "txt", "md": return "doc.text"
        default: return "doc"
        }
    }

    private func fileType(for name: String) -> String {
        let ext = URL(fileURLWithPath: name).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg": return "JPEG image"
        case "png": return "PNG image"
        case "pdf": return "PDF document"
        case "txt": return "Text document"
        case "mp4", "mov": return "Video"
        case "zip": return "Archive"
        case "": return "File"
        default: return "\(ext.uppercased()) file"
        }
    }

    private func transferDate(_ date: Date) -> String {
        let time = date.formatted(date: .omitted, time: .shortened)
        if Calendar.current.isDateInToday(date) {
            return "Today, \(time)"
        }
        if Calendar.current.isDateInYesterday(date) {
            return "Yesterday, \(time)"
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    // MARK: - 3. CLIPBOARD PAGE
    private var clipboardPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center) {
                pageHeading("Shared clipboard", subtitle: "Your most recent text, shared between Android and Mac.")
                Spacer()
                Button {
                    model.syncClipboardNow()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 11, weight: .semibold))
                        Text("Sync now")
                    }
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }

            // Green Status Banner
            HStack(spacing: 12) {
                Image(systemName: "doc.badge.checkmark")
                    .font(.system(size: 16))
                    .foregroundStyle(palette.success)

                VStack(alignment: .leading, spacing: 2) {
                    Text(model.isConnected ? "Clipboard sync is on" : "Clipboard sync paused")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(palette.text)

                    Text(model.isConnected ? "Text and images sync automatically over local Wi-Fi" : "Connect your phone to resume sync")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)
                }

                Spacer()

                Toggle("", isOn: $model.isClipboardSyncEnabled)
                    .labelsHidden()
                    .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
            }
            .padding(14)
            .background(palette.successBannerBg, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(palette.successBannerBorder, lineWidth: 1)
            )

            // Current Clipboard & Recent Snippets
            HStack {
                Text("Recent snippets")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(palette.text)

                Spacer()

                if !model.recentClipboardSnippets.isEmpty {
                    Button("Clear history") {
                        model.clearClipboardHistory()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(palette.muted)
                }
            }

            if model.recentClipboardSnippets.isEmpty {
                // If no stored history, show current clipboard snippet
                let currentClip = NSPasteboard.general.string(forType: .string) ?? ""
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Label("Current Mac Clipboard", systemImage: "laptopcomputer")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)

                        Spacer()

                        Button {
                            copyCurrentClipboard()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "doc.on.doc")
                                    .font(.system(size: 10))
                                Text("Copy")
                                    .font(.system(size: 11.5, weight: .medium))
                            }
                            .padding(.horizontal, 9)
                            .frame(height: 26)
                            .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }

                    Text(currentClip.isEmpty ? "No clipboard content yet. Copied text will appear here." : currentClip)
                        .font(.system(size: 13))
                        .foregroundStyle(currentClip.isEmpty ? palette.muted : palette.text)
                        .textSelection(.enabled)
                        .lineLimit(4)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .bridgeCard(palette)
            } else {
                VStack(spacing: 14) {
                    ForEach(model.recentClipboardSnippets) { snippet in
                        clipboardSnippetCard(snippet)
                    }
                }
            }

            // Footer Privacy Note
            HStack(spacing: 6) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 10))
                Text("Only text is synced. History stays on this Mac and clears after 24 hours.")
                    .font(.system(size: 11))
            }
            .foregroundStyle(palette.muted)
            .padding(.top, 4)
        }
    }

    private func clipboardSnippetCard(_ snippet: ClipboardSnippetItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: snippet.origin.contains("Mac") ? "laptopcomputer" : "iphone")
                        .font(.system(size: 11))
                    Text(snippet.origin)
                        .font(.system(size: 12, weight: .medium))
                    Text(snippet.date.formatted(date: .omitted, time: .shortened))
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)
                }
                .foregroundStyle(palette.muted)

                Spacer()

                if snippet.isCurrent {
                    Text("On your clipboard")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.success)
                }

                Button {
                    model.copySnippet(snippet.text)
                    copiedSnippetId = snippet.id
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        if copiedSnippetId == snippet.id {
                            copiedSnippetId = nil
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: copiedSnippetId == snippet.id ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 10))
                        Text(copiedSnippetId == snippet.id ? "Copied" : "Copy")
                            .font(.system(size: 11.5, weight: .medium))
                    }
                    .padding(.horizontal, 9)
                    .frame(height: 26)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }

            if snippet.isLink {
                Link(snippet.text, destination: URL(string: snippet.text) ?? URL(string: "https://google.com")!)
                    .font(.system(size: 13))
                    .foregroundStyle(palette.linkAccent)
                    .lineLimit(2)
            } else {
                Text(snippet.text)
                    .font(.system(size: 13))
                    .foregroundStyle(palette.text)
                    .textSelection(.enabled)
                    .lineLimit(3)
            }

            Text(snippet.isLink ? "Link" : "Text")
                .font(.system(size: 10.5))
                .foregroundStyle(palette.muted)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .bridgeCard(palette)
    }

    // MARK: - 4. NOTIFICATIONS PAGE
    private var notificationsPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center) {
                pageHeading("Android notifications", subtitle: "Notifications from your phone, in one place.")
                Spacer()
                Button {
                    page = .preferences
                    preferencesTab = 1
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 11))
                        Text("Preferences")
                    }
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 12)
                    .frame(height: 30)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }

            // Status Banner
            HStack(spacing: 12) {
                Image(systemName: model.isConnected ? "checkmark.circle" : "clock")
                    .font(.system(size: 16))
                    .foregroundStyle(model.isConnected ? palette.success : palette.muted)

                Text(model.isConnected ? "Up to date with \(deviceName)" : "Waiting for your phone")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(palette.text)

                Spacer()

                Text("Sync notifications")
                    .font(.system(size: 12))
                    .foregroundStyle(palette.text)

                Toggle("", isOn: $model.showAndroidNotifications)
                    .labelsHidden()
                    .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
            }
            .padding(14)
            .background(palette.successBannerBg, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(palette.successBannerBorder, lineWidth: 1)
            )

            if model.recentNotifications.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bell.slash")
                        .font(.system(size: 26))
                        .foregroundStyle(palette.muted.opacity(0.7))
                    Text("No notifications yet")
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundStyle(palette.text)
                    Text("Notifications from your phone will appear here in real time.")
                        .font(.system(size: 12))
                        .foregroundStyle(palette.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(40)
                .bridgeCard(palette)
            } else {
                HStack {
                    Text("Recent · \(model.recentNotifications.count)")
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(palette.text)

                    Spacer()

                    Button("Dismiss all") {
                        model.dismissAllNotifications()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(palette.linkAccent)
                }

                VStack(spacing: 12) {
                    ForEach(model.recentNotifications) { item in
                        notificationCard(item)
                    }
                }
            }

            // Footer note
            Text("Dismissing here also dismisses the notification on your phone.")
                .font(.system(size: 11.5))
                .foregroundStyle(palette.muted)
                .padding(.top, 4)
        }
    }

    private func notificationCard(_ item: NotificationHistoryItem) -> some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(palette.secondarySurface)
                Image(systemName: notificationSymbol(for: item.app))
                    .font(.system(size: 15))
                    .foregroundStyle(Color(red: 64/255, green: 66/255, blue: 69/255))
            }
            .frame(width: 36, height: 36)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(item.app)
                        .font(.system(size: 12))
                        .foregroundStyle(palette.muted)

                    if item.isUnread {
                        Circle()
                            .fill(palette.linkAccent)
                            .frame(width: 5, height: 5)
                    }

                    Spacer()

                    Text(item.date.formatted(date: .omitted, time: .shortened))
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)

                    Button {
                        model.dismissNotification(item)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(palette.muted)
                    }
                    .buttonStyle(.plain)
                    .padding(.leading, 4)
                }

                if !item.title.isEmpty {
                    Text(item.title)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(palette.text)
                }

                if !item.body.isEmpty {
                    Text(item.body)
                        .font(.system(size: 12.5))
                        .foregroundStyle(palette.muted)
                        .lineLimit(2)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .bridgeCard(palette)
    }

    private func notificationSymbol(for app: String) -> String {
        let normalized = app.lowercased()
        if normalized.contains("message") { return "bubble.left" }
        if normalized.contains("slack") { return "number" }
        if normalized.contains("calendar") { return "calendar" }
        if normalized.contains("gmail") || normalized.contains("mail") { return "envelope" }
        return "bell"
    }

    // MARK: - 5. DEVICES PAGE
    private var devicesPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center) {
                pageHeading("Paired devices", subtitle: "Manage the connection between your Android and Mac.")
                Spacer()
                Button {
                    openPairing(from: .devices)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "plus")
                            .font(.system(size: 11, weight: .semibold))
                        Text("Pair a device…")
                    }
                }
                .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)
            }

            if model.hasPairedDevice {
                pairedDeviceSection
            } else {
                noPairedDevicesCard
            }
        }
    }

    private var noPairedDevicesCard: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(palette.secondarySurface)
                    .frame(width: 56, height: 56)
                Image(systemName: "iphone.slash")
                    .font(.system(size: 24))
                    .foregroundStyle(palette.muted)
            }

            VStack(spacing: 4) {
                Text("No paired devices")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.text)

                Text("Pair your Android phone to start syncing notifications, files, and clipboard.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(palette.muted)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                Button {
                    openPairing(from: .devices)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "qrcode")
                            .font(.system(size: 12, weight: .semibold))
                        Text("Open Setup & Pair")
                    }
                }
                .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)

                Button {
                    model.replayOnboarding()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "sparkles")
                            .font(.system(size: 12))
                        Text("View Setup Guide")
                    }
                }
                .buttonStyle(.plain)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(palette.linkAccent)
                .padding(.horizontal, 14)
                .frame(height: 34)
                .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(palette.border, lineWidth: 1)
                )
            }
        }
        .padding(.vertical, 48)
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity)
        .bridgeCard(palette)
    }

    private var pairedDeviceSection: some View {
        VStack(alignment: .leading, spacing: 20) {
            // Main Paired Device Card
            VStack(spacing: 0) {
                HStack(alignment: .center, spacing: 20) {
                    PixelPhoneGraphic(width: 50, height: 90, showDate: false, cornerRadius: 12)

                    VStack(alignment: .leading, spacing: 5) {
                        HStack(spacing: 8) {
                            Text(deviceName)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(palette.text)

                            HStack(spacing: 4) {
                                Circle()
                                    .fill(model.isConnected ? palette.success : palette.muted)
                                    .frame(width: 5, height: 5)
                                Text(model.isConnected ? "Connected" : "Offline")
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(model.isConnected ? palette.success : palette.muted)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(palette.successBannerBg, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                        }

                        Text("Android · Bridge connection")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.muted)

                        HStack(spacing: 12) {
                            if let batt = batteryText {
                                HStack(spacing: 4) {
                                    Image(systemName: model.isPeerCharging ? "battery.100percent.bolt" : "battery.100percent")
                                        .font(.system(size: 11))
                                    Text("\(batt) battery")
                                }
                            }
                            Text(model.isConnected ? "Online" : "Disconnected")
                        }
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    if model.isConnected {
                        Button("Disconnect") {
                            model.disconnect()
                        }
                        .buttonStyle(.plain)
                        .font(.system(size: 12.5, weight: .medium))
                        .padding(.horizontal, 14)
                        .frame(height: 30)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(palette.border, lineWidth: 1)
                        )
                    } else {
                        Button("Reconnect") {
                            model.reconnect()
                        }
                        .bridgePrimaryButton(color: palette.accent, textColor: palette.buttonText)
                    }
                }
                .padding(20)

                Divider()
                    .overlay(palette.border)

                // 3 Column Stats
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Connection")
                            .font(.system(size: 11))
                            .foregroundStyle(palette.muted)
                        Text(model.isConnected ? "Local Wi-Fi" : "Disconnected")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Network")
                            .font(.system(size: 11))
                            .foregroundStyle(palette.muted)
                        Text(model.networkName)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Device address")
                            .font(.system(size: 11))
                            .foregroundStyle(palette.muted)
                        Text(model.deviceIpAddress)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)
            }
            .bridgeCard(palette)

            // Connected Services Section
            Text("Connected services")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)
                .padding(.top, 4)

            VStack(spacing: 0) {
                serviceRow("File transfers", subtitle: "Send and receive while this device is connected.") {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(model.isConnected ? palette.success : palette.muted)
                            .frame(width: 5, height: 5)
                        Text(model.isConnected ? "Ready" : "Waiting")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(model.isConnected ? palette.success : palette.muted)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.successBannerBg, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                }

                Divider()
                    .overlay(palette.border)

                serviceRow("Clipboard sync", subtitle: "Share copied text in both directions.") {
                    Toggle("", isOn: $model.isClipboardSyncEnabled)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }

                Divider()
                    .overlay(palette.border)

                serviceRow("Notification sync", subtitle: "Show Android notifications on this Mac.") {
                    Toggle("", isOn: $model.showAndroidNotifications)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }
            }
            .bridgeCard(palette)

            // Footer note
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.shield")
                        .font(.system(size: 12))
                    Text("Only devices you pair can connect. Your data never leaves your network.")
                        .font(.system(size: 11.5))
                }
                .foregroundStyle(palette.muted)

                Spacer()

                Button("Forget device…") {
                    showForgetConfirm = true
                }
                .buttonStyle(.plain)
                .font(.system(size: 11.5))
                .foregroundStyle(Color(red: 220/255, green: 38/255, blue: 38/255))
            }
            .padding(.top, 4)
        }
    }

    private func serviceRow<Trailing: View>(_ title: String, subtitle: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(palette.text)
                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(palette.muted)
            }

            Spacer()

            trailing()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    // MARK: - 6. PAIR A DEVICE PAGE
    private var pairDevicePage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top) {
                pageHeading("Pair your Android", subtitle: "Connect your phone to this Mac with the Bridge companion app.")
                Spacer()
                Button("Cancel") {
                    page = pageBeforePairing
                }
                .buttonStyle(.plain)
                .font(.system(size: 12.5, weight: .medium))
                .padding(.horizontal, 14)
                .frame(height: 30)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .stroke(palette.border, lineWidth: 1)
                )
            }

            // Main Pair Box
            VStack(spacing: 24) {
                HStack(alignment: .top, spacing: 44) {
                    // Left Column: QR Code + Code Display
                    VStack(spacing: 14) {
                        if let qr = pairingQRCode {
                            Image(nsImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 190, height: 190)
                                .padding(10)
                                .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .stroke(palette.border, lineWidth: 1)
                                )
                        }

                        VStack(spacing: 4) {
                            Text("Or enter this pairing code")
                                .font(.system(size: 11.5))
                                .foregroundStyle(palette.muted)

                            HStack(spacing: 8) {
                                Text(model.pairingCode)
                                    .font(.system(size: 26, weight: .bold, design: .rounded))
                                    .foregroundStyle(palette.text)

                                Button {
                                    model.copySnippet(model.pairingCode)
                                    pairingCodeCopied = true
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                        pairingCodeCopied = false
                                    }
                                } label: {
                                    Image(systemName: pairingCodeCopied ? "checkmark" : "doc.on.doc")
                                        .font(.system(size: 13))
                                        .foregroundStyle(palette.muted)
                                }
                                .buttonStyle(.plain)
                            }

                            Text("Expires in 04:58 · Refresh")
                                .font(.system(size: 11))
                                .foregroundStyle(palette.muted)
                                .padding(.top, 2)
                        }
                    }
                    .frame(width: 260)

                    // Right Column: Steps
                    VStack(alignment: .leading, spacing: 22) {
                        Text("Start on your Android")
                            .font(.system(size: 14.5, weight: .semibold))
                            .foregroundStyle(palette.text)

                        stepItem(number: "1", title: "Open Bridge on your phone", detail: "Install the Bridge companion app if you haven’t already.")
                        stepItem(number: "2", title: "Choose “Pair with Mac”", detail: "Keep both devices on the same Wi-Fi network.")
                        stepItem(number: "3", title: "Scan this QR code", detail: "Or choose “Enter code” and type the six digits shown here.")

                        Link("Get Bridge for Android ↗", destination: URL(string: "https://github.com/satyam-mohanty/Bridge/releases/latest")!)
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(palette.linkAccent)
                            .padding(.leading, 32)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // Bottom Status Pill inside Card
                HStack(spacing: 8) {
                    Image(systemName: "rays")
                        .font(.system(size: 12))
                    Text(model.isConnected ? "Your Android is connected!" : "Waiting for your Android to connect…")
                        .font(.system(size: 12))
                }
                .foregroundStyle(palette.muted)
                .frame(maxWidth: .infinity)
                .frame(height: 38)
                .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .padding(28)
            .bridgeCard(palette)

            // Security note
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "checkmark.shield")
                    .font(.system(size: 13))
                VStack(alignment: .leading, spacing: 2) {
                    Text("A private connection, by design.")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(palette.text)

                    Text("Pairing creates an encrypted link between your devices. Only share this code with a device you trust.")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)
                }
            }
            .padding(.top, 4)
        }
    }

    private func stepItem(number: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(palette.secondarySurface)
                Text(number)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(palette.text)
            }
            .frame(width: 20, height: 20)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(palette.text)

                Text(detail)
                    .font(.system(size: 11.5))
                    .foregroundStyle(palette.muted)
            }
        }
    }

    private var pairingQRCode: NSImage? {
        guard !model.pairingJson.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(model.pairingJson.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }

    private func openPairing(from source: BridgePage) {
        pageBeforePairing = source
        page = .pairDevice
    }

    // MARK: - 7. PREFERENCES PAGE
    private var preferencesPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .center) {
                pageHeading("Preferences", subtitle: preferencesSubtitle)

                Spacer()

                HStack(spacing: 2) {
                    preferencesTabButton("General", tag: 0)
                    preferencesTabButton("Notifications", tag: 1)
                    preferencesTabButton("About", tag: 2)
                }
                .padding(3)
                .background(palette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            if preferencesTab == 0 {
                generalPreferencesView
            } else if preferencesTab == 1 {
                notificationsPreferencesView
            } else {
                aboutPreferencesView
            }
        }
    }

    private var preferencesSubtitle: String {
        switch preferencesTab {
        case 0: return "A few things to make Bridge your own."
        case 1: return "Choose what makes it across to your Mac."
        default: return "About Bridge, the developer, and privacy policy."
        }
    }

    private func preferencesTabButton(_ label: String, tag: Int) -> some View {
        Button {
            preferencesTab = tag
        } label: {
            Text(label)
                .font(.system(size: 12.5, weight: preferencesTab == tag ? .medium : .regular))
                .foregroundStyle(preferencesTab == tag ? palette.text : palette.muted)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(
                    preferencesTab == tag ? palette.surface : Color.clear,
                    in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                )
                .shadow(color: preferencesTab == tag ? Color.black.opacity(0.04) : .clear, radius: 2, y: 1)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Preferences: General
    private var generalPreferencesView: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Downloads")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(palette.secondarySurface)
                    Image(systemName: "arrow.down.doc")
                        .font(.system(size: 17))
                        .foregroundStyle(palette.text)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 3) {
                    Text("Save received files to Downloads")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(palette.text)
                    Text(model.downloadsDirectoryPath)
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)
                }

                Spacer()

                HStack(spacing: 10) {
                    Button("Change…") {
                        model.chooseDownloadsDirectory()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 12)
                    .frame(height: 28)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )

                    Button("Open") {
                        model.openDownloadsDirectory()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 12)
                    .frame(height: 28)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )
                }
            }
            .padding(18)
            .bridgeCard(palette)

            Text("App behavior")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Launch Bridge at login")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Keep your devices connected when you start your Mac.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Toggle("", isOn: Binding(get: { model.isOpenAtLogin }, set: { _ in model.toggleOpenAtLogin() }))
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }
                .padding(18)

                Divider()
                    .overlay(palette.border)

                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Hide Bridge from Dock")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Bridge will still be available in the menu bar.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Toggle("", isOn: $model.hideFromDock)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }
                .padding(18)
            }
            .bridgeCard(palette)

            Text("Connection & troubleshooting")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(model.isConnected ? "Connected to \(deviceName)" : "Waiting for \(deviceName)")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Reconnect if syncing isn’t working as expected.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    HStack(spacing: 10) {
                        Button {
                            model.reconnect()
                        } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 11))
                                Text("Reconnect")
                            }
                            .font(.system(size: 12, weight: .medium))
                            .padding(.horizontal, 12)
                            .frame(height: 28)
                            .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .stroke(palette.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)

                        Button("Diagnostics…") {
                            showDiagnosticsSheet = true
                        }
                        .buttonStyle(.plain)
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .frame(height: 28)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(palette.border, lineWidth: 1)
                        )
                    }
                }
                .padding(18)

                Divider()
                    .overlay(palette.border)

                HStack {
                    Text("Resetting removes this trusted connection. You’ll need to pair again.")
                        .font(.system(size: 11.5))
                        .foregroundStyle(palette.muted)

                    Spacer()

                    Button("Reset Pairing…") {
                        showClearConfirm = true
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 220/255, green: 38/255, blue: 38/255))
                }
                .padding(18)
            }
            .bridgeCard(palette)

            Text("About & developer")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("About Bridge")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Built by Satyam Mohanty · Privacy Policy & Details")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Button {
                        preferencesTab = 2
                    } label: {
                        HStack(spacing: 5) {
                            Text("Open About")
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11))
                        }
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .frame(height: 28)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(palette.border, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
                .padding(18)
            }
            .bridgeCard(palette)

            HStack {
                Button("Bridge 1.4.2 · Built by Satyam Mohanty") {
                    preferencesTab = 2
                }
                .buttonStyle(.plain)
                .font(.system(size: 11))
                .foregroundStyle(palette.muted)

                Spacer()

                Button("Check for updates") {
                    updateManager.checkForUpdates()
                }
                .buttonStyle(.plain)
                .font(.system(size: 11))
                .foregroundStyle(palette.linkAccent)
            }
            .padding(.top, 4)
        }
    }

    // MARK: - Preferences: Notifications
    private var notificationsPreferencesView: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 12) {
                Image(systemName: "bell")
                    .font(.system(size: 16))
                    .foregroundStyle(palette.success)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Notification sync is on")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(palette.text)

                    Text("Android access granted · macOS notifications allowed")
                        .font(.system(size: 11))
                        .foregroundStyle(palette.muted)
                }

                Spacer()

                Button("System Settings ↗") {
                    model.openNotificationSettings()
                }
                .buttonStyle(.plain)
                .font(.system(size: 12))
                .foregroundStyle(palette.linkAccent)

                Toggle("", isOn: $model.showAndroidNotifications)
                    .labelsHidden()
                    .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
            }
            .padding(14)
            .background(palette.successBannerBg, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(palette.successBannerBorder, lineWidth: 1)
            )

            // Dynamic Apps discovered from notifications
            let discoveredApps = Array(Set(model.recentNotifications.map { $0.app })).sorted()
            HStack {
                Text("Apps on \(deviceName)")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(palette.text)

                Spacer()

                Text("\(discoveredApps.count) apps")
                    .font(.system(size: 12))
                    .foregroundStyle(palette.muted)
            }

            VStack(spacing: 0) {
                if discoveredApps.isEmpty {
                    VStack(spacing: 6) {
                        Text("No apps discovered yet")
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Apps will appear here once notifications are received from your phone.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(24)
                } else {
                    ForEach(discoveredApps.indices, id: \.self) { idx in
                        let app = discoveredApps[idx]
                        appNotificationRow(symbol: notificationSymbol(for: app), name: app, subtitle: "Synced notifications", key: app)
                        if idx < discoveredApps.count - 1 {
                            Divider().overlay(palette.border)
                        }
                    }
                }
            }
            .bridgeCard(palette)

            Text("Privacy & focus")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Show message previews in banners")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("When off, banners show only the app and sender.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Toggle("", isOn: $model.showNotificationPreviews)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }
                .padding(18)

                Divider()
                    .overlay(palette.border)

                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Respect Focus on Mac")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                        Text("Keep banners quiet while a Focus is active.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Toggle("", isOn: $model.respectFocusOnMac)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
                }
                .padding(18)
            }
            .bridgeCard(palette)

            Text("These settings affect this Mac only. Your Android notifications stay unchanged.")
                .font(.system(size: 11.5))
                .foregroundStyle(palette.muted)
                .padding(.top, 4)
        }
    }

    private func appNotificationRow(symbol: String, name: String, subtitle: String, key: String) -> some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(palette.secondarySurface)
                Image(systemName: symbol)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 64/255, green: 66/255, blue: 69/255))
            }
            .frame(width: 36, height: 36)

            Text(name)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(palette.text)
                .frame(width: 140, alignment: .leading)

            Text(subtitle)
                .font(.system(size: 12))
                .foregroundStyle(palette.muted)

            Spacer()

            Toggle("", isOn: Binding(
                get: { model.appNotificationSettings[key] ?? true },
                set: { model.appNotificationSettings[key] = $0 }
            ))
            .labelsHidden()
            .toggleStyle(SwitchToggleStyle(tint: palette.toggleTint))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
    }

    // MARK: - Preferences: About
    private var aboutPreferencesView: some View {
        VStack(alignment: .leading, spacing: 20) {
            // App & Developer Info Card
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(palette.secondarySurface)
                        BridgeMark()
                            .frame(width: 36, height: 36)
                    }
                    .frame(width: 58, height: 58)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(palette.border, lineWidth: 1)
                    )

                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("Bridge")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(palette.text)

                            Text(updateManager.currentVersionString)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(palette.muted)
                        }

                        Text("Cross-device continuity, clipboard sync, notifications & files.")
                            .font(.system(size: 12.5))
                            .foregroundStyle(palette.muted)
                    }

                    Spacer()

                    Button {
                        updateManager.checkForUpdates()
                    } label: {
                        HStack(spacing: 6) {
                            if updateManager.isChecking {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                    .font(.system(size: 11))
                                Text("Check for Updates")
                            }
                        }
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .frame(height: 30)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(palette.border, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!updateManager.canCheckForUpdates || updateManager.isChecking)
                }

                Divider()
                    .overlay(palette.border)

                // Highlighted developer statement
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "sparkles")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(palette.text)
                            .padding(.top, 1)

                        Text("Built by Satyam Mohanty, an independent developer who builds cool things.")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(palette.text)
                    }

                    HStack(spacing: 12) {
                        Link(destination: URL(string: "https://www.satyammohanty.com")!) {
                            HStack(spacing: 5) {
                                Image(systemName: "globe")
                                Text("Website")
                            }
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(palette.linkAccent)
                        }

                        Text("·").foregroundStyle(palette.muted)

                        Link(destination: URL(string: "https://github.com/satyam-mohanty")!) {
                            HStack(spacing: 5) {
                                Image(systemName: "curlybraces")
                                Text("GitHub")
                            }
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(palette.linkAccent)
                        }

                        Text("·").foregroundStyle(palette.muted)

                        Link(destination: URL(string: "https://x.com/isatymm")!) {
                            HStack(spacing: 5) {
                                Image(systemName: "bubble.left")
                                Text("X / Twitter")
                            }
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(palette.linkAccent)
                        }

                        Text("·").foregroundStyle(palette.muted)

                        Link(destination: URL(string: "https://www.linkedin.com/in/satyam-mohanty")!) {
                            HStack(spacing: 5) {
                                Image(systemName: "person.crop.circle")
                                Text("LinkedIn")
                            }
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(palette.linkAccent)
                        }
                    }
                    .padding(.top, 2)
                }
            }
            .padding(18)
            .bridgeCard(palette)

            // Privacy Policy Section
            Text("Privacy Policy")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(palette.text)

            VStack(spacing: 0) {
                privacyRow(
                    icon: "lock.shield",
                    title: "Privacy First & Zero Telemetry",
                    description: "Bridge contains zero tracking SDKs, zero advertising frameworks, and zero analytics beacons. We do not collect, process, or sell any personal data."
                )

                Divider().overlay(palette.border)

                privacyRow(
                    icon: "network",
                    title: "100% Local Peer-to-Peer Communication",
                    description: "All transfers occur exclusively across your local Wi-Fi network (LAN) over encrypted TCP port 48250. Your data never touches any external cloud server or intermediate relay."
                )

                Divider().overlay(palette.border)

                privacyRow(
                    icon: "key.fill",
                    title: "End-to-End Cryptography",
                    description: "Every payload—clipboard text, images, and file packets—is encrypted end-to-end using authenticated AES-256-GCM. Pairing keys are stored securely inside macOS Keychain."
                )

                Divider().overlay(palette.border)

                privacyRow(
                    icon: "eye.slash.fill",
                    title: "Notification Masking & Local Storage",
                    description: "Previews can be masked at any time. Synced notification history and clipboard snippets remain strictly on your local device and can be cleared whenever you choose."
                )
            }
            .bridgeCard(palette)

            HStack {
                Text("Bridge · © 2026 Satyam Mohanty. All rights reserved.")
                    .font(.system(size: 11))
                    .foregroundStyle(palette.muted)

                Spacer()
            }
            .padding(.top, 4)
        }
    }

    private func privacyRow(icon: String, title: String, description: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(palette.secondarySurface)
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundStyle(palette.text)
            }
            .frame(width: 34, height: 34)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(palette.text)
                Text(description)
                    .font(.system(size: 11.5))
                    .foregroundStyle(palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()
        }
        .padding(16)
    }

    // MARK: - Helper Methods
    private func copyCurrentClipboard() {
        guard !clipboardText.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(clipboardText, forType: .string)
    }

    private func handleDroppedFiles(providers: [NSItemProvider]) {
        for provider in providers {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                    DispatchQueue.main.async { model.sendFiles(urls: [url]) }
                } else if let url = item as? URL {
                    DispatchQueue.main.async { model.sendFiles(urls: [url]) }
                }
            }
        }
    }

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
                    .background(palette.secondarySurface)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
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
        .background(palette.canvas)
    }

    private func formatByteCount(_ bytes: Int) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }
}

// MARK: - View Extension
public extension View {
    func bridgeCard(_ palette: BridgePalette) -> some View {
        background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(palette.surface)
        )
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(palette.dark ? 0.22 : 0.65),
                            Color.white.opacity(palette.dark ? 0.04 : 0.15),
                            Color.clear
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 0.8
                )
        )
        .shadow(
            color: Color.black.opacity(palette.dark ? 0.35 : 0.06),
            radius: 12,
            x: 0,
            y: 4
        )
    }

    func bridgePrimaryButton(color: Color = Color(red: 0, green: 122/255, blue: 1), textColor: Color = .white) -> some View {
        font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 16)
            .frame(height: 32)
            .background(
                LinearGradient(
                    colors: [
                        color.opacity(0.95),
                        color
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.28), lineWidth: 0.5)
            )
            .shadow(color: color.opacity(0.30), radius: 4, x: 0, y: 2)
            .foregroundStyle(textColor)
            .buttonStyle(.plain)
    }
}

// MARK: - Native Window Dragging & Screen Transition Helpers

public struct WindowDragArea: NSViewRepresentable {
    public init() {}

    public func makeNSView(context: Context) -> WindowDragView {
        WindowDragView()
    }

    public func updateNSView(_ nsView: WindowDragView, context: Context) {}
}

public final class WindowDragView: NSView {
    public override var mouseDownCanMoveWindow: Bool { true }

    public override func hitTest(_ point: NSPoint) -> NSView? {
        self
    }

    public override func mouseDown(with event: NSEvent) {
        if event.clickCount == 2 {
            window?.zoom(nil)
            return
        }
        if let window = self.window {
            window.performDrag(with: event)
        } else {
            super.mouseDown(with: event)
        }
    }
}

public struct WindowConfigurator: NSViewRepresentable {
    public init() {}

    public func makeNSView(context: Context) -> WindowConfiguratorNSView {
        WindowConfiguratorNSView()
    }

    public func updateNSView(_ nsView: WindowConfiguratorNSView, context: Context) {}
}

public final class WindowConfiguratorNSView: NSView {
    private var screenChangeObserver: NSObjectProtocol?
    private var backingObserver: NSObjectProtocol?

    public override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard let window = self.window else { return }

        // Window sizing & dragging
        window.isMovableByWindowBackground = true
        window.isMovable = true
        window.minSize = NSSize(width: 900, height: 640)

        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.styleMask.insert(.fullSizeContentView)

        // Smooth CoreAnimation layering without continuous redraw cascades
        window.contentView?.wantsLayer = true
        window.contentView?.layerContentsRedrawPolicy = .onSetNeedsDisplay

        // Observe screen changes smoothly without jitter
        if let obs = screenChangeObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = backingObserver { NotificationCenter.default.removeObserver(obs) }

        screenChangeObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didChangeScreenNotification,
            object: window,
            queue: .main
        ) { [weak window] _ in
            guard let window = window else { return }
            window.invalidateShadow()
            window.resetCursorRects()
        }

        backingObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didChangeBackingPropertiesNotification,
            object: window,
            queue: .main
        ) { [weak window] _ in
            guard let window = window else { return }
            window.invalidateShadow()
        }
    }

    deinit {
        if let obs = screenChangeObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = backingObserver { NotificationCenter.default.removeObserver(obs) }
    }
}

public extension Notification.Name {
    static let openAboutPage = Notification.Name("openAboutPage")
}
