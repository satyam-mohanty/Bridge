import SwiftUI

public struct MenuBarView: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var updateManager = UpdateManager.shared
    @State private var diagnosticsCopied = false
    @Environment(\.colorScheme) private var colorScheme

    private var isDark: Bool { colorScheme == .dark }

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        VStack(spacing: 8) {
            // Control Center Status Card
            headerStatusCard

            Divider()
                .padding(.horizontal, 4)

            // Primary Navigation & Operations
            VStack(spacing: 2) {
                menuRow(title: "Open Bridge Window", icon: "macwindow", badgeColor: Color(red: 0, green: 122/255, blue: 1)) {
                    NSApp.activate(ignoringOtherApps: true)
                    for window in NSApp.windows {
                        if window.canBecomeMain {
                            window.makeKeyAndOrderFront(nil)
                            return
                        }
                    }
                }

                menuRow(
                    title: "Send Files…",
                    icon: "paperplane.fill",
                    badgeColor: Color(red: 0, green: 122/255, blue: 1),
                    disabled: !model.isConnected
                ) {
                    model.selectAndSendFiles()
                }

                menuRow(title: "Pair Phone…", icon: "qrcode", badgeColor: Color(red: 175/255, green: 82/255, blue: 222/255)) {
                    PairingWindowManager.shared.show(model: model)
                }

                menuRow(title: "Setup & Onboarding…", icon: "hand.wave.fill", badgeColor: Color(red: 52/255, green: 199/255, blue: 89/255)) {
                    NSApp.activate(ignoringOtherApps: true)
                    for window in NSApp.windows {
                        if window.canBecomeMain {
                            window.makeKeyAndOrderFront(nil)
                            break
                        }
                    }
                    model.replayOnboarding()
                }

                menuRow(title: "Settings…", icon: "gearshape.fill", badgeColor: Color(red: 142/255, green: 142/255, blue: 147/255)) {
                    NSApp.activate(ignoringOtherApps: true)
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                }

                menuRow(title: "About Bridge…", icon: "info.circle.fill", badgeColor: Color(red: 88/255, green: 86/255, blue: 214/255)) {
                    NSApp.activate(ignoringOtherApps: true)
                    for window in NSApp.windows {
                        if window.canBecomeMain {
                            window.makeKeyAndOrderFront(nil)
                            break
                        }
                    }
                    NotificationCenter.default.post(name: .openAboutPage, object: nil)
                }

                menuRow(
                    title: updateManager.isChecking ? "Checking for Updates…" : "Check for Updates…",
                    icon: "arrow.triangle.2.circlepath",
                    badgeColor: Color(red: 50/255, green: 173/255, blue: 230/255),
                    disabled: !updateManager.canCheckForUpdates || updateManager.isChecking
                ) {
                    updateManager.checkForUpdates()
                }
            }

            Divider()
                .padding(.horizontal, 4)

            // Connection Management
            VStack(spacing: 2) {
                if model.isConnected {
                    menuRow(title: "Disconnect Phone", icon: "bolt.slash.fill", badgeColor: Color(red: 255/255, green: 149/255, blue: 0)) {
                        model.disconnect()
                    }
                }

                menuRow(title: "Reconnect", icon: "arrow.clockwise", badgeColor: Color(red: 52/255, green: 199/255, blue: 89/255)) {
                    model.reconnect()
                }

                menuRow(title: "Clear & Reset Pairing", icon: "trash.fill", badgeColor: Color(red: 255/255, green: 59/255, blue: 48/255)) {
                    model.clearAndResetPairing()
                }
            }

            Divider()
                .padding(.horizontal, 4)

            // Quick Preferences
            VStack(spacing: 6) {
                Toggle("Hide from Dock", isOn: $model.hideFromDock)
                    .toggleStyle(.switch)
                    .font(.system(size: 12))
                    .padding(.horizontal, 8)

                Toggle("Open at Login", isOn: Binding(
                    get: { model.isOpenAtLogin },
                    set: { _ in model.toggleOpenAtLogin() }
                ))
                .toggleStyle(.switch)
                .font(.system(size: 12))
                .padding(.horizontal, 8)
            }
            .padding(.vertical, 2)

            Divider()
                .padding(.horizontal, 4)

            // Diagnostics & Links
            VStack(spacing: 2) {
                menuRow(
                    title: diagnosticsCopied ? "Diagnostics Copied!" : "Copy Diagnostics",
                    icon: diagnosticsCopied ? "checkmark" : "doc.on.clipboard",
                    badgeColor: Color(red: 142/255, green: 142/255, blue: 147/255)
                ) {
                    model.copyDiagnostics()
                    diagnosticsCopied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        diagnosticsCopied = false
                    }
                }

                Link(destination: URL(string: "https://buymeacoffee.com/satyammohanty")!) {
                    HStack(spacing: 10) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(Color(red: 255/255, green: 204/255, blue: 0))
                            Image(systemName: "cup.and.saucer.fill")
                                .font(.system(size: 11))
                                .foregroundStyle(.black)
                        }
                        .frame(width: 22, height: 22)

                        Text("Buy Me a Coffee")
                            .font(.system(size: 12.5, weight: .regular))
                            .foregroundStyle(Color(nsColor: .labelColor))

                        Spacer()

                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 10))
                            .foregroundStyle(Color(nsColor: .tertiaryLabelColor))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HoverRowButtonStyle())
            }

            Divider()
                .padding(.horizontal, 4)

            // Quit Row
            menuRow(title: "Quit Bridge", icon: "power", badgeColor: Color(nsColor: .secondaryLabelColor)) {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding(10)
        .frame(width: 260)
        .background(.regularMaterial)
    }

    // MARK: - Subviews

    private var headerStatusCard: some View {
        HStack(spacing: 10) {
            BridgeMark()
                .frame(width: 32, height: 32)
                .shadow(color: Color.black.opacity(0.10), radius: 3, x: 0, y: 1.5)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(model.isConnected ? Color(red: 52/255, green: 199/255, blue: 89/255) : Color(red: 255/255, green: 149/255, blue: 0))
                        .frame(width: 7, height: 7)
                        .shadow(color: (model.isConnected ? Color.green : Color.orange).opacity(0.6), radius: 2)

                    Text(model.isConnected ? model.connectedDeviceName : "Listening…")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color(nsColor: .labelColor))
                        .lineLimit(1)
                }

                HStack(spacing: 6) {
                    Text("Port 48250 · LAN")
                        .font(.system(size: 10.5))
                        .foregroundStyle(Color(nsColor: .secondaryLabelColor))

                    if model.isConnected, let phoneBattery = model.peerBatteryLevel {
                        Text("·")
                            .font(.system(size: 10.5))
                            .foregroundStyle(Color(nsColor: .tertiaryLabelColor))

                        HStack(spacing: 3) {
                            Image(systemName: model.isPeerCharging ? "battery.100percent.bolt" : "battery.75percent")
                                .font(.system(size: 10))
                                .foregroundStyle(model.isPeerCharging ? Color.green : Color(nsColor: .secondaryLabelColor))
                            Text("\(phoneBattery)%")
                                .font(.system(size: 10.5, weight: .medium))
                                .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                        }
                    }
                }
            }

            Spacer(minLength: 0)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor).opacity(0.4))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.white.opacity(isDark ? 0.12 : 0.4), lineWidth: 0.8)
        )
    }

    private func menuRow(
        title: String,
        icon: String,
        badgeColor: Color,
        disabled: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(badgeColor.opacity(disabled ? 0.3 : 1.0))
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white)
                }
                .frame(width: 22, height: 22)

                Text(title)
                    .font(.system(size: 12.5))
                    .foregroundStyle(disabled ? Color(nsColor: .disabledControlTextColor) : Color(nsColor: .labelColor))

                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(HoverRowButtonStyle())
        .disabled(disabled)
    }
}

// MARK: - Apple macOS Hover Button Style
private struct HoverRowButtonStyle: ButtonStyle {
    @State private var isHovered = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(
                        configuration.isPressed
                            ? Color(nsColor: .selectedContentBackgroundColor).opacity(0.35)
                            : (isHovered ? Color(nsColor: .selectedContentBackgroundColor).opacity(0.18) : Color.clear)
                    )
            )
            .onHover { hovering in
                isHovered = hovering
            }
    }
}
