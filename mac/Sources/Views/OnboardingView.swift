import SwiftUI
import AppKit
import ServiceManagement

public enum MacOnboardingStep: Int, CaseIterable {
    case welcome = 0
    case notifications = 1
    case clipboard = 2
    case downloads = 3
    case openAtLogin = 4
    case pairing = 5
    case complete = 6

    public var title: String {
        switch self {
        case .welcome: return "Welcome to Bridge"
        case .notifications: return "Mac Notifications"
        case .clipboard: return "Universal Clipboard"
        case .downloads: return "File Transfers & Downloads"
        case .openAtLogin: return "Seamless Background Sync"
        case .pairing: return "Pair Your Android Device"
        case .complete: return "You're All Set!"
        }
    }

    public var subtitle: String {
        switch self {
        case .welcome:
            return "Cross-device continuity between your Mac and Android phone, built for speed and privacy."
        case .notifications:
            return "Receive your Android phone notifications as native macOS alerts on your desktop."
        case .clipboard:
            return "Copy text and links on your phone or Mac and paste immediately on the other device."
        case .downloads:
            return "AirDrop-style LAN transfers send files directly to your chosen folder with zero upload limit."
        case .openAtLogin:
            return "Start Bridge automatically when you log in so your devices connect effortlessly."
        case .pairing:
            return "Scan the QR code or enter the secure 6-digit key with Bridge on your Android phone."
        case .complete:
            return "Bridge is configured and ready to keep your phone and Mac in sync."
        }
    }
}

public struct MacOnboardingView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var currentStep: MacOnboardingStep = .welcome
    @State private var notificationStatusChecked: Bool = false
    @State private var notificationsAllowed: Bool = false
    @State private var copiedCode: Bool = false

    private var isDark: Bool { colorScheme == .dark }

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ZStack {
            VisualEffectBackground(material: .underWindowBackground, blendingMode: .behindWindow)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Top Header bar with Step progress indicator
                headerView
                    .padding(.horizontal, 28)
                    .padding(.top, 22)
                    .padding(.bottom, 12)

                Divider()
                    .opacity(0.4)

                // Main Step Content
                VStack(spacing: 0) {
                    stepContentView
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .padding(.horizontal, 36)
                .padding(.vertical, 24)

                Divider()
                    .opacity(0.4)

                // Footer with Next/Back and Skip/Decline buttons
                footerView
                    .padding(.horizontal, 28)
                    .padding(.vertical, 18)
                    .background(Color(nsColor: .windowBackgroundColor).opacity(0.4))
            }
        }
        .frame(width: 660, height: 600)
        .onAppear {
            refreshNotificationStatus()
        }
    }

    // MARK: - Header
    private var headerView: some View {
        HStack(alignment: .center) {
            BridgeMark()
                .frame(width: 32, height: 32)
                .shadow(color: Color.black.opacity(0.12), radius: 4, x: 0, y: 2)

            VStack(alignment: .leading, spacing: 2) {
                Text("Bridge Setup Guide")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color(nsColor: .labelColor))
                Text("Step \(currentStep.rawValue + 1) of \(MacOnboardingStep.allCases.count)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
            }

            Spacer()

            // Skip to app directly at any time
            Button {
                model.completeOnboarding()
                dismiss()
            } label: {
                Text("Skip to App")
                    .font(.system(size: 11, weight: .regular))
                    .foregroundStyle(Color(nsColor: .tertiaryLabelColor))
            }
            .buttonStyle(.plain)
            .padding(.trailing, 10)

            // Progress Pills
            HStack(spacing: 6) {
                ForEach(MacOnboardingStep.allCases, id: \.self) { step in
                    Capsule()
                        .fill(
                            step.rawValue == currentStep.rawValue
                                ? Color(red: 0, green: 122/255, blue: 1)
                                : (step.rawValue < currentStep.rawValue
                                   ? Color(red: 52/255, green: 199/255, blue: 89/255)
                                   : Color(nsColor: .separatorColor).opacity(0.6))
                        )
                        .frame(width: step.rawValue == currentStep.rawValue ? 20 : 7, height: 6)
                        .animation(.spring(response: 0.35, dampingFraction: 0.75), value: currentStep)
                }
            }
        }
    }

    // MARK: - Step Content Switcher
    @ViewBuilder
    private var stepContentView: some View {
        VStack(spacing: 18) {
            VStack(spacing: 6) {
                Text(currentStep.title)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Color(nsColor: .labelColor))
                    .multilineTextAlignment(.center)

                Text(currentStep.subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .frame(maxWidth: 500)
            }
            .padding(.top, 4)

            Spacer(minLength: 8)

            // Step dynamic body
            switch currentStep {
            case .welcome:
                welcomeStepCard
            case .notifications:
                notificationsStepCard
            case .clipboard:
                clipboardStepCard
            case .downloads:
                downloadsStepCard
            case .openAtLogin:
                openAtLoginStepCard
            case .pairing:
                pairingStepCard
            case .complete:
                completeStepCard
            }

            Spacer(minLength: 8)
        }
    }

    // MARK: - Step 0: Welcome
    private var welcomeStepCard: some View {
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                featureItem(
                    icon: "bell.badge.fill",
                    color: Color(red: 255/255, green: 59/255, blue: 48/255),
                    title: "Phone Notifications",
                    desc: "See Android calls and app messages on Mac"
                )
                featureItem(
                    icon: "doc.on.clipboard.fill",
                    color: Color(red: 0, green: 122/255, blue: 1),
                    title: "Universal Clipboard",
                    desc: "Instant copy & paste across both devices"
                )
            }

            HStack(spacing: 16) {
                featureItem(
                    icon: "arrow.left.arrow.right.circle.fill",
                    color: Color(red: 52/255, green: 199/255, blue: 89/255),
                    title: "Direct File Transfer",
                    desc: "Fast LAN sharing with zero cloud tracking"
                )
                featureItem(
                    icon: "lock.shield.fill",
                    color: Color(red: 175/255, green: 82/255, blue: 222/255),
                    title: "Encrypted & Local",
                    desc: "End-to-end ChaCha20-Poly1305 security"
                )
            }
        }
        .frame(maxWidth: 520)
    }

    private func featureItem(icon: String, color: Color, title: String, desc: String) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(color.opacity(0.16))
                    .frame(width: 38, height: 38)
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(color)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(Color(nsColor: .labelColor))
                Text(desc)
                    .font(.system(size: 11))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor).opacity(0.5))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
        )
        .frame(maxWidth: .infinity)
    }

    // MARK: - Step 1: Notifications
    private var notificationsStepCard: some View {
        VStack(spacing: 14) {
            statusBadge(
                isConfigured: notificationsAllowed && model.showAndroidNotifications,
                configuredText: "Notifications are Enabled",
                waitingText: model.showAndroidNotifications ? "System Authorization Pending" : "Notifications Disabled"
            )

            VStack(alignment: .leading, spacing: 10) {
                Toggle("Show Android alerts on Mac", isOn: $model.showAndroidNotifications)
                    .font(.system(size: 13, weight: .medium))

                Toggle("Show message previews in banners", isOn: $model.showNotificationPreviews)
                    .font(.system(size: 13))
                    .disabled(!model.showAndroidNotifications)
                    .padding(.leading, 18)
            }
            .padding(14)
            .frame(maxWidth: 520, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.45))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )

            // Primary settings opener
            HStack(spacing: 12) {
                Button {
                    MacNotificationManager.shared.requestAuthorization { granted in
                        DispatchQueue.main.async {
                            self.notificationsAllowed = granted
                            if granted {
                                self.model.showAndroidNotifications = true
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "bell.badge.fill")
                        Text("Grant System Permission")
                    }
                    .font(.system(size: 12.5, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    model.openNotificationSettings()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "gearshape")
                        Text("Open macOS Notification Settings")
                    }
                    .font(.system(size: 12))
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Step 2: Clipboard
    private var clipboardStepCard: some View {
        VStack(spacing: 14) {
            statusBadge(
                isConfigured: model.isClipboardSyncEnabled,
                configuredText: "Clipboard Sync Active",
                waitingText: "Clipboard Sync Off"
            )

            VStack(alignment: .leading, spacing: 10) {
                Toggle("Sync clipboard seamlessly with Android", isOn: $model.isClipboardSyncEnabled)
                    .font(.system(size: 13, weight: .semibold))

                Text("Text and web links copied on either device become immediately available on the other device over your secure local Wi-Fi. Passwords and sensitive data stay private on your local network.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(16)
            .frame(maxWidth: 520, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.45))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )

            HStack(spacing: 12) {
                Button {
                    model.isClipboardSyncEnabled = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "checkmark")
                        Text("Enable Universal Clipboard")
                    }
                    .font(.system(size: 12.5, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    model.isClipboardSyncEnabled = false
                } label: {
                    Text("I don't want clipboard sync")
                        .font(.system(size: 12))
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Step 3: Downloads
    private var downloadsStepCard: some View {
        VStack(spacing: 14) {
            statusBadge(
                isConfigured: !model.downloadsDirectoryPath.isEmpty,
                configuredText: "Downloads Directory Ready",
                waitingText: "No Directory Selected"
            )

            VStack(alignment: .leading, spacing: 10) {
                Text("Received files are saved to:")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))

                HStack(spacing: 8) {
                    Image(systemName: "folder.fill")
                        .foregroundColor(.accentColor)
                    Text(model.downloadsDirectoryPath.isEmpty ? "~/Downloads" : model.downloadsDirectoryPath)
                        .font(.system(size: 12.5, weight: .semibold, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                }
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                )

                Text("Files, images, and videos sent from your Android phone will be securely streamed to this folder over your high-speed local network.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
            }
            .padding(16)
            .frame(maxWidth: 520, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.45))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )

            HStack(spacing: 12) {
                Button {
                    model.chooseDownloadsDirectory()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "folder.badge.gearshape")
                        Text("Choose Custom Folder…")
                    }
                    .font(.system(size: 12.5, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    let defaultPath = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first?.path ?? "~/Downloads"
                    model.downloadsDirectoryPath = defaultPath
                    UserDefaults.standard.set(defaultPath, forKey: "downloadsDirectory")
                } label: {
                    Text("Keep Default Downloads")
                        .font(.system(size: 12))
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Step 4: Launch at Login
    private var openAtLoginStepCard: some View {
        VStack(spacing: 14) {
            statusBadge(
                isConfigured: model.isOpenAtLogin,
                configuredText: "Open at Login Enabled",
                waitingText: "Manual Launch Only"
            )

            VStack(alignment: .leading, spacing: 10) {
                Toggle("Launch Bridge at Login", isOn: Binding(
                    get: { model.isOpenAtLogin },
                    set: { _ in model.toggleOpenAtLogin() }
                ))
                .font(.system(size: 13, weight: .semibold))

                Toggle("Hide Bridge icon from Dock (Menu Bar only)", isOn: $model.hideFromDock)
                    .font(.system(size: 12.5))

                Text("Running Bridge in the background consumes negligible resources (< 15 MB RAM, 0% CPU idle) while ensuring instant pairing whenever your Android device is nearby.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
            }
            .padding(16)
            .frame(maxWidth: 520, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.45))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )

            HStack(spacing: 12) {
                Button {
                    if !model.isOpenAtLogin {
                        model.toggleOpenAtLogin()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "checkmark")
                        Text("Enable Launch at Login")
                    }
                    .font(.system(size: 12.5, weight: .medium))
                    .padding(.horizontal, 14)
                    .frame(height: 32)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    if model.isOpenAtLogin {
                        model.toggleOpenAtLogin()
                    }
                } label: {
                    Text("I'll launch manually")
                        .font(.system(size: 12))
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Step 5: Pairing
    private var pairingStepCard: some View {
        VStack(spacing: 12) {
            statusBadge(
                isConfigured: model.isConnected || model.hasPairedDevice,
                configuredText: model.isConnected ? "Connected to \(model.connectedDeviceName)" : "Paired with \(model.connectedDeviceName)",
                waitingText: "Waiting to scan QR or enter code"
            )

            HStack(spacing: 24) {
                // QR code preview
                VStack(spacing: 6) {
                    if let image = generateQRCode(from: model.pairingJson) {
                        Image(nsImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 140, height: 140)
                            .padding(8)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .strokeBorder(Color.black.opacity(0.1), lineWidth: 1)
                            )
                            .shadow(color: Color.black.opacity(0.08), radius: 6, x: 0, y: 2)
                    } else {
                        Rectangle()
                            .fill(Color.secondary.opacity(0.2))
                            .frame(width: 140, height: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    Text("Scan with Phone Camera")
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                }

                // Instructions
                VStack(alignment: .leading, spacing: 12) {
                    Text("1. Open Bridge on Android")
                        .font(.system(size: 13, weight: .medium))
                    Text("2. Tap Scan QR Code")
                        .font(.system(size: 13, weight: .medium))
                    Text("3. Point camera at this screen to pair")
                        .font(.system(size: 13, weight: .medium))

                    Button("Regenerate Pairing Key") {
                        model.regenerateKey()
                    }
                    .font(.system(size: 11))
                    .buttonStyle(.borderless)
                    .padding(.top, 4)
                }
            }
            .padding(14)
            .frame(maxWidth: 520)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.45))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )
        }
    }

    // MARK: - Step 6: Complete
    private var completeStepCard: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(Color(red: 52/255, green: 199/255, blue: 89/255).opacity(0.15))
                    .frame(width: 64, height: 64)
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 38))
                    .foregroundStyle(Color(red: 52/255, green: 199/255, blue: 89/255))
            }

            VStack(spacing: 6) {
                Text("Setup Finished")
                    .font(.system(size: 16, weight: .bold))
                Text("Bridge is now running seamlessly in your menu bar. You can access settings, send files, or adjust preferences anytime.")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 440)
            }

            // Summary Table
            VStack(spacing: 8) {
                summaryRow(title: "macOS Notifications", state: model.showAndroidNotifications ? "Enabled" : "Off")
                summaryRow(title: "Universal Clipboard", state: model.isClipboardSyncEnabled ? "Enabled" : "Off")
                summaryRow(title: "Save Location", state: (model.downloadsDirectoryPath as NSString).lastPathComponent)
                summaryRow(title: "Launch at Login", state: model.isOpenAtLogin ? "Enabled" : "Off")
            }
            .padding(12)
            .frame(maxWidth: 440)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor).opacity(0.4))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color(nsColor: .separatorColor).opacity(0.3), lineWidth: 0.8)
            )
        }
    }

    private func summaryRow(title: String, state: String) -> some View {
        HStack {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(Color(nsColor: .secondaryLabelColor))
            Spacer()
            Text(state)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color(nsColor: .labelColor))
        }
    }

    // MARK: - Status Badge
    private func statusBadge(isConfigured: Bool, configuredText: String, waitingText: String) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(isConfigured ? Color(red: 52/255, green: 199/255, blue: 89/255) : Color(red: 255/255, green: 149/255, blue: 0))
                .frame(width: 8, height: 8)

            Text(isConfigured ? configuredText : waitingText)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(isConfigured ? Color(red: 52/255, green: 199/255, blue: 89/255) : Color(red: 255/255, green: 149/255, blue: 0))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(
            Capsule()
                .fill(
                    (isConfigured
                     ? Color(red: 52/255, green: 199/255, blue: 89/255)
                     : Color(red: 255/255, green: 149/255, blue: 0)
                    ).opacity(0.12)
                )
        )
    }

    // MARK: - Footer
    private var footerView: some View {
        HStack(spacing: 12) {
            if currentStep != .welcome {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        goToPreviousStep()
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text("Back")
                    }
                    .font(.system(size: 12.5, weight: .medium))
                    .frame(height: 32)
                    .padding(.horizontal, 12)
                }
                .buttonStyle(.bordered)
            }

            Spacer()

            if currentStep == .complete {
                Button {
                    model.completeOnboarding()
                    dismiss()
                } label: {
                    HStack(spacing: 6) {
                        Text("Start Using Bridge")
                        Image(systemName: "arrow.right")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .frame(height: 34)
                    .background(
                        LinearGradient(
                            colors: [
                                Color(red: 0, green: 122/255, blue: 1),
                                Color(red: 0, green: 105/255, blue: 230/255)
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                    )
                }
                .buttonStyle(.plain)
            } else {
                // Secondary "Skip / I'll do this later" option
                Button {
                    if currentStep == .welcome {
                        model.completeOnboarding()
                        dismiss()
                    } else {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            goToNextStep()
                        }
                    }
                } label: {
                    Text(currentStep == .welcome ? "Skip Setup for Now" : "Skip step")
                        .font(.system(size: 12))
                        .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                        .padding(.horizontal, 10)
                        .frame(height: 32)
                }
                .buttonStyle(.plain)

                // Primary Next / Continue
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        goToNextStep()
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(currentStep == .welcome ? "Get Started" : "Continue")
                        Image(systemName: "chevron.right")
                    }
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .frame(height: 32)
                    .background(
                        LinearGradient(
                            colors: [
                                Color(red: 0, green: 122/255, blue: 1),
                                Color(red: 0, green: 105/255, blue: 230/255)
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Navigation Helpers
    private func goToNextStep() {
        if let next = MacOnboardingStep(rawValue: currentStep.rawValue + 1) {
            currentStep = next
            if currentStep == .notifications {
                refreshNotificationStatus()
            }
        }
    }

    private func goToPreviousStep() {
        if let prev = MacOnboardingStep(rawValue: currentStep.rawValue - 1) {
            currentStep = prev
        }
    }

    private func refreshNotificationStatus() {
        MacNotificationManager.shared.checkAuthorization { granted in
            DispatchQueue.main.async {
                self.notificationsAllowed = granted
                self.notificationStatusChecked = true
            }
        }
    }

    private func generateQRCode(from string: String) -> NSImage? {
        guard !string.isEmpty else { return nil }
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")

        guard let output = filter.outputImage else { return nil }
        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaled = output.transformed(by: transform)

        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: 180, height: 180))
    }
}
