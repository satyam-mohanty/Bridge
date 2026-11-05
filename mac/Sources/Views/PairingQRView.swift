import SwiftUI
import CoreImage.CIFilterBuiltins

public struct PairingQRView: View {
    let pairingJson: String
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var copied: Bool = false

    private var isDark: Bool { colorScheme == .dark }

    public init(pairingJson: String, model: AppModel) {
        self.pairingJson = pairingJson
        self.model = model
    }

    public var body: some View {
        ZStack {
            VisualEffectBackground(material: .popover, blendingMode: .behindWindow)
                .ignoresSafeArea()

            VStack(spacing: 20) {
                // Header with App Brand
                VStack(spacing: 6) {
                    BridgeMark()
                        .frame(width: 44, height: 44)
                        .shadow(color: Color.black.opacity(0.12), radius: 6, x: 0, y: 3)

                    Text("Pair Your Phone")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Color(nsColor: .labelColor))

                    Text("Scan with the Bridge camera on your phone to connect instantly.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(Color(nsColor: .secondaryLabelColor))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 280)
                }
                .padding(.top, 8)

                // QR Code Liquid Glass Card
                if let image = generateQRCode(from: pairingJson) {
                    VStack {
                        Image(nsImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 180, height: 180)
                            .padding(12)
                    }
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [
                                        Color.white.opacity(0.8),
                                        Color.white.opacity(0.2),
                                        Color.black.opacity(0.08)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1
                            )
                    )
                    .shadow(color: Color.black.opacity(isDark ? 0.35 : 0.08), radius: 12, x: 0, y: 5)
                } else {
                    Text("Failed to generate QR code")
                        .font(.system(size: 12))
                        .foregroundColor(.red)
                        .frame(width: 204, height: 204)
                }

                // Or enter 6-digit code capsule
                VStack(spacing: 6) {
                    Text("Or enter this pairing code")
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(Color(nsColor: .secondaryLabelColor))

                    HStack(spacing: 10) {
                        Text(model.pairingCode)
                            .font(.system(size: 22, weight: .bold, design: .monospaced))
                            .tracking(2)
                            .foregroundStyle(Color(nsColor: .labelColor))

                        Button {
                            model.copySnippet(model.pairingCode)
                            copied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                copied = false
                            }
                        } label: {
                            Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc")
                                .font(.system(size: 14))
                                .foregroundStyle(copied ? Color(red: 52/255, green: 199/255, blue: 89/255) : Color(nsColor: .secondaryLabelColor))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(
                        Capsule()
                            .fill(Color(nsColor: .controlBackgroundColor).opacity(0.55))
                    )
                    .background(
                        Capsule()
                            .fill(.ultraThinMaterial)
                    )
                    .overlay(
                        Capsule()
                            .strokeBorder(Color.white.opacity(isDark ? 0.2 : 0.6), lineWidth: 0.8)
                    )
                }

                Spacer(minLength: 0)

                // Actions Footer
                HStack(spacing: 12) {
                    Button {
                        model.regenerateKey()
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 11, weight: .semibold))
                            Text("Regenerate Key")
                                .font(.system(size: 12, weight: .medium))
                        }
                        .padding(.horizontal, 14)
                        .frame(height: 32)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(Color(nsColor: .controlBackgroundColor).opacity(0.50))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .strokeBorder(Color(nsColor: .separatorColor).opacity(0.4), lineWidth: 0.8)
                        )
                        .foregroundStyle(Color(nsColor: .labelColor))
                    }
                    .buttonStyle(.plain)

                    Button {
                        dismiss()
                    } label: {
                        Text("Done")
                            .font(.system(size: 12.5, weight: .semibold))
                            .padding(.horizontal, 22)
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
                            .overlay(
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .strokeBorder(Color.white.opacity(0.3), lineWidth: 0.5)
                            )
                            .shadow(color: Color(red: 0, green: 122/255, blue: 1).opacity(0.3), radius: 4, x: 0, y: 2)
                            .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut(.defaultAction)
                }
                .padding(.bottom, 6)
            }
            .padding(24)
        }
        .frame(width: 380, height: 490)
    }

    private func generateQRCode(from string: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        let data = Data(string.utf8)
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")

        guard let outputImage = filter.outputImage else { return nil }
        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaledImage = outputImage.transformed(by: transform)

        let rep = NSCIImageRep(ciImage: scaledImage)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)
        return nsImage
    }
}
