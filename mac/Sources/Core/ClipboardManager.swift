import Foundation
import AppKit
import CryptoKit

public final class ClipboardManager {
    public static let shared = ClipboardManager()
    
    private var pollTimer: Timer?
    private var lastChangeCount: Int = -1
    private var isAwake: Bool = true
    private var isConnected: Bool = false
    
    // Loop prevention: track last received and sent hashes
    private var lastRemoteReceivedHash: String?
    private var lastLocalSentHash: String?
    private var lastLocalSentTime: TimeInterval = 0
    private let lock = NSLock()
    
    private init() {
        lastChangeCount = NSPasteboard.general.changeCount
    }
    
    public func updateConnectionStatus(isConnected: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.isConnected = isConnected
            self.evaluatePolling()
        }
    }
    
    public func updateAwakeStatus(isAwake: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.isAwake = isAwake
            self.evaluatePolling()
        }
    }
    
    private func evaluatePolling() {
        if isConnected && isAwake {
            startPolling()
        } else {
            stopPolling()
        }
    }
    
    private func startPolling() {
        guard pollTimer == nil else { return }
        lastChangeCount = NSPasteboard.general.changeCount
        
        // 0.4s interval with 0.1s leeway
        let timer = Timer(timeInterval: 0.4, repeats: true) { [weak self] _ in
            self?.checkForClipboardChanges()
        }
        timer.tolerance = 0.1
        RunLoop.main.add(timer, forMode: .common)
        self.pollTimer = timer
        LogManager.shared.log("Started clipboard polling (0.4s interval)")
    }
    
    private func stopPolling() {
        guard pollTimer != nil else { return }
        pollTimer?.invalidate()
        pollTimer = nil
        LogManager.shared.log("Stopped clipboard polling (idle/asleep/disconnected)")
    }
    
    private func checkForClipboardChanges() {
        let currentCount = NSPasteboard.general.changeCount
        guard currentCount != lastChangeCount else { return }
        lastChangeCount = currentCount
        
        let pasteboard = NSPasteboard.general
        
        // Check for Image first
        if pasteboard.canReadItem(withDataConformingToTypes: [NSPasteboard.PasteboardType.png.rawValue, NSPasteboard.PasteboardType.tiff.rawValue]),
           let image = NSImage(pasteboard: pasteboard) {
            handleLocalImageCopied(image)
            return
        }
        
        // Check for Text
        if let string = pasteboard.string(forType: .string), !string.isEmpty {
            handleLocalTextCopied(string)
            return
        }
    }

    /// Immediately sends the current text or image clipboard to the connected device.
    public func syncCurrentClipboard() {
        guard isConnected else { return }
        let pasteboard = NSPasteboard.general
        lastChangeCount = pasteboard.changeCount

        if pasteboard.canReadItem(withDataConformingToTypes: [NSPasteboard.PasteboardType.png.rawValue, NSPasteboard.PasteboardType.tiff.rawValue]),
           let image = NSImage(pasteboard: pasteboard) {
            handleLocalImageCopied(image)
        } else if let text = pasteboard.string(forType: .string), !text.isEmpty {
            handleLocalTextCopied(text)
        }
    }
    
    private func handleLocalTextCopied(_ text: String) {
        guard let textData = text.data(using: .utf8) else { return }
        let hashHex = ImageProcessor.sha256Hex(of: textData).lowercased()
        let now = Date().timeIntervalSince1970
        
        lock.lock()
        if hashHex == lastRemoteReceivedHash {
            lastRemoteReceivedHash = nil
            lock.unlock()
            return
        }
        if hashHex == lastLocalSentHash && (now - lastLocalSentTime < 1.5) {
            lock.unlock()
            return
        }
        lastLocalSentHash = hashHex
        lastLocalSentTime = now
        lastRemoteReceivedHash = nil
        lock.unlock()
        
        LogManager.shared.log("Local text copied (\(text.count) chars), sending to Android")
        let msg = ClipboardTextMessage(origin: "mac", hash: hashHex, text: text)
        NetworkServer.shared.send(type: .clipboardText, value: msg)
    }
    
    private func handleLocalImageCopied(_ image: NSImage) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            guard let processed = ImageProcessor.processImageForSending(image) else {
                return
            }
            
            let hashHex = processed.hashHex.lowercased()
            let now = Date().timeIntervalSince1970
            
            self.lock.lock()
            if hashHex == self.lastRemoteReceivedHash {
                self.lastRemoteReceivedHash = nil
                self.lock.unlock()
                return
            }
            if hashHex == self.lastLocalSentHash && (now - self.lastLocalSentTime < 1.5) {
                self.lock.unlock()
                return
            }
            self.lastLocalSentHash = hashHex
            self.lastLocalSentTime = now
            self.lastRemoteReceivedHash = nil
            self.lock.unlock()
            
            LogManager.shared.log("Local image copied (\(processed.data.count) bytes, \(processed.totalChunks) chunks, \(processed.mime)), sending to Android")
            
            let transferId = UUID().uuidString.lowercased()
            let startMsg = ClipboardImageStartMessage(
                id: transferId,
                origin: "mac",
                hash: processed.hashHex,
                mime: processed.mime,
                size: processed.data.count,
                totalChunks: processed.totalChunks
            )
            NetworkServer.shared.send(type: .clipboardImageStart, value: startMsg)
            
            let chunks = ImageProcessor.chunkData(processed.data)
            for (index, chunk) in chunks.enumerated() {
                let chunkObj = ClipboardImageChunkData(
                    transferId: transferId,
                    chunkIndex: UInt32(index),
                    chunkBytes: chunk
                )
                NetworkServer.shared.sendRaw(type: .clipboardImageChunk, payload: chunkObj.serialize())
            }
        }
    }
    
    public func handleRemoteTextReceived(_ text: String, hash: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let lowerHash = hash.lowercased()
            
            self.lock.lock()
            self.lastRemoteReceivedHash = lowerHash
            self.lock.unlock()
            
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            self.lastChangeCount = pasteboard.changeCount
            LogManager.shared.log("Applied remote text from Android to clipboard (\(text.count) chars)")
        }
    }
    
    public func handleRemoteImageReceived(_ data: Data, hash: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let lowerHash = hash.lowercased()
            
            self.lock.lock()
            self.lastRemoteReceivedHash = lowerHash
            self.lock.unlock()
            
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            
            guard let image = NSImage(data: data) else {
                LogManager.shared.log("Failed to create NSImage from received image bytes (\(data.count) bytes)", level: .error)
                return
            }
            
            let item = NSPasteboardItem()
            
            // 1. PNG representation for browsers, Electron, Discord, Slack, etc.
            if let rep = (image.representations.first as? NSBitmapImageRep) ?? (image.tiffRepresentation.flatMap { NSBitmapImageRep(data: $0) }),
               let pngData = rep.representation(using: .png, properties: [:]) {
                item.setData(pngData, forType: .png)
            } else {
                item.setData(data, forType: .png)
            }
            
            // 2. TIFF representation for native AppKit paste targets
            if let tiffData = image.tiffRepresentation {
                item.setData(tiffData, forType: .tiff)
            }
            
            pasteboard.writeObjects([item])
            self.lastChangeCount = pasteboard.changeCount
            LogManager.shared.log("Applied remote image from Android to clipboard (\(data.count) bytes)")
        }
    }
    
}
