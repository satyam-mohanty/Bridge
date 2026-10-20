import Foundation
import AppKit
import CryptoKit

public enum ImageProcessor {
    public static let maxDimension: CGFloat = 4096.0
    public static let maxPngSizeBytes: Int = 10 * 1024 * 1024 // 10 MB
    public static let chunkSizeBytes: Int = 64 * 1024 // 64 KB
    
    public struct ProcessedImage {
        public let data: Data
        public let mime: String
        public let hashHex: String
        public let totalChunks: Int
    }
    
    public static func processImageForSending(_ image: NSImage) -> ProcessedImage? {
        guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
            return nil
        }
        
        let originalWidth = CGFloat(cgImage.width)
        let originalHeight = CGFloat(cgImage.height)
        
        var targetWidth = originalWidth
        var targetHeight = originalHeight
        
        if originalWidth > maxDimension || originalHeight > maxDimension {
            let scale = min(maxDimension / originalWidth, maxDimension / originalHeight)
            targetWidth = (originalWidth * scale).rounded()
            targetHeight = (originalHeight * scale).rounded()
        }
        
        let finalCgImage: CGImage
        if targetWidth != originalWidth || targetHeight != originalHeight {
            guard let colorSpace = cgImage.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(
                    data: nil,
                    width: Int(targetWidth),
                    height: Int(targetHeight),
                    bitsPerComponent: cgImage.bitsPerComponent,
                    bytesPerRow: 0,
                    space: colorSpace,
                    bitmapInfo: cgImage.bitmapInfo.rawValue
                  ) else {
                return nil
            }
            context.interpolationQuality = .high
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))
            guard let resized = context.makeImage() else {
                return nil
            }
            finalCgImage = resized
        } else {
            finalCgImage = cgImage
        }
        
        let rep = NSBitmapImageRep(cgImage: finalCgImage)
        rep.size = NSSize(width: targetWidth, height: targetHeight)
        
        var mime = "image/png"
        var imageData = rep.representation(using: .png, properties: [:])
        
        if let data = imageData, data.count > maxPngSizeBytes {
            // PNG exceeds 10 MB, compress to JPEG
            if let jpegData = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) {
                imageData = jpegData
                mime = "image/jpeg"
            }
        }
        
        guard let finalData = imageData, !finalData.isEmpty else {
            return nil
        }
        
        let hash = SHA256.hash(data: finalData)
        let hashHex = hash.map { String(format: "%02x", $0) }.joined()
        let totalChunks = Int(ceil(Double(finalData.count) / Double(chunkSizeBytes)))
        
        return ProcessedImage(data: finalData, mime: mime, hashHex: hashHex, totalChunks: totalChunks)
    }
    
    public static func chunkData(_ data: Data) -> [Data] {
        var chunks: [Data] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSizeBytes, data.count)
            chunks.append(data.subdata(in: offset..<end))
            offset = end
        }
        return chunks
    }
    
    public static func sha256Hex(of data: Data) -> String {
        let hash = SHA256.hash(data: data)
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
