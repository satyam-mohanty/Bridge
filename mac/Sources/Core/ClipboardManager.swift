import Foundation
import AppKit
import CryptoKit

public final class ClipboardManager {
    public static let shared = ClipboardManager()
    
    private var pollTimer: Timer?
    private var lastChangeCount: Int = -1
    private var isAwake: Bool = true
    private var isConnected: Bool = false
    
    // Loop prevention: store recent clip hashes
    private var seenHashes: Set<String> = []
    private var recentHashesQueue: [String] = []
    private let maxSeenHashes = 50
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
    
    private func handleLocalTextCopied(_ text: String) {
        guard let textData = text.data(using: .utf8) else { return }
        let hashHex = ImageProcessor.sha256Hex(of: textData)
        
        if isHashSeen(hashHex) {
            return
        }
        recordSeenHash(hashHex)
        
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
            
            if self.isHashSeen(processed.hashHex) {
                return
            }
            self.recordSeenHash(processed.hashHex)
            
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
            if self.isHashSeen(hash) {
                return
            }
            self.recordSeenHash(hash)
            
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
            if self.isHashSeen(hash) {
                return
            }
            self.recordSeenHash(hash)
            
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setData(data, forType: .png)
            self.lastChangeCount = pasteboard.changeCount
            LogManager.shared.log("Applied remote image from Android to clipboard (\(data.count) bytes)")
        }
    }
    
    private func isHashSeen(_ hash: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return seenHashes.contains(hash.lowercased())
    }
    
    private func recordSeenHash(_ hash: String) {
        lock.lock()
        defer { lock.unlock() }
        let lower = hash.lowercased()
        seenHashes.insert(lower)
        recentHashesQueue.append(lower)
        if recentHashesQueue.count > maxSeenHashes {
            let removed = recentHashesQueue.removeFirst()
            seenHashes.remove(removed)
        }
    }
}
