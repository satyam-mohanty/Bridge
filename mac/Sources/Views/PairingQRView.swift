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
            HStack(spacing: 8) {
                BridgeMark()
                    .frame(width: 32, height: 32)
                Text("Bridge")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Color(red: 41/255, green: 43/255, blue: 46/255))
            }
            .padding(.top, 4)
            
            Text("Pair your Android")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color(red: 41/255, green: 43/255, blue: 46/255))
            
            if let image = generateQRCode(from: pairingJson) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 190, height: 190)
                    .padding(10)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .stroke(Color(red: 227/255, green: 227/255, blue: 222/255), lineWidth: 1)
                    )
            } else {
                Text("Failed to generate QR code")
                    .font(.system(size: 12))
                    .foregroundColor(.red)
                    .frame(width: 190, height: 190)
            }
            
            VStack(spacing: 4) {
                Text("Or enter this pairing code")
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color(red: 142/255, green: 144/255, blue: 148/255))
                
                HStack(spacing: 8) {
                    Text(model.pairingCode)
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(Color(red: 41/255, green: 43/255, blue: 46/255))
                    
                    Button {
                        model.copySnippet(model.pairingCode)
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                            copied = false
                        }
                    } label: {
                        Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 13))
                            .foregroundStyle(Color(red: 142/255, green: 144/255, blue: 148/255))
                    }
                    .buttonStyle(.plain)
                }
            }
            
            HStack(spacing: 12) {
                Button {
                    model.regenerateKey()
                } label: {
                    Text("Regenerate Key")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .frame(height: 28)
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(Color(red: 227/255, green: 227/255, blue: 222/255), lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                
                Button {
                    dismiss()
                } label: {
                    Text("Done")
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 16)
                        .frame(height: 28)
                        .background(Color(red: 35/255, green: 35/255, blue: 38/255), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 4)
        }
        .padding(24)
        .frame(width: 360, height: 460)
        .background(Color(red: 250/255, green: 249/255, blue: 247/255))
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
