import SwiftUI
import CoreImage.CIFilterBuiltins

public struct PairingQRView: View {
    let pairingJson: String
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var copied: Bool = false
    
    public init(pairingJson: String, model: AppModel) {
        self.pairingJson = pairingJson
        self.model = model
    }
    
    public var body: some View {
        VStack(spacing: 16) {
            if let logoUrl = Bundle.main.url(forResource: "BridgeLogo", withExtension: "png"),
               let image = NSImage(contentsOf: logoUrl) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 44, height: 44)
            }
            
            Text("Pair with Android Phone")
                .font(.headline)
            
            if let image = generateQRCode(from: pairingJson) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 220, height: 220)
                    .padding(8)
                    .background(Color.white)
                    .cornerRadius(12)
                    .shadow(radius: 4)
            } else {
                Text("Failed to generate QR code")
                    .foregroundColor(.red)
            }
            
            Text("Open Bridge on your phone and tap 'Scan QR Code'.\nBoth devices must be on the same Wi-Fi or hotspot.")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
            
            HStack(spacing: 12) {
                Button(action: {
                    model.copyPairingJson()
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        copied = false
                    }
                }) {
                    Label(copied ? "Copied!" : "Copy Pairing Code", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                
                Button("Regenerate Key") {
                    model.regenerateKey()
                }
            }
            
            Divider()
            
            Button("Done") {
                dismiss()
            }
            .keyboardShortcut(.defaultAction)
        }
        .padding(24)
        .frame(width: 360, height: 440)
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
