import Foundation
import Network

public protocol NetworkServerDelegate: AnyObject {
    func serverDidUpdateConnectionStatus(isConnected: Bool, clientDescription: String?)
    func serverDidReceiveText(text: String, hash: String)
    func serverDidReceiveImage(data: Data, hash: String)
    func serverDidReceiveFile(name: String, savedUrl: URL, size: Int)
    func serverFileTransferProgress(transferId: String, name: String, progress: Double, isIncoming: Bool)
    func serverDidReceiveNotificationPost(_ msg: NotifPostMessage)
    func serverDidReceiveNotificationRemove(_ msg: NotifRemoveMessage)
    func serverDidReceiveIconResponse(_ msg: IconRespMessage)
    func serverDidReceiveCallEvent(_ msg: CallEventMessage)
    func serverDidReceiveLockRequest()
}

public final class NetworkServer {
    public static let shared = NetworkServer()
    
    public weak var delegate: NetworkServerDelegate?
    
    private let port: NWEndpoint.Port = 48250
    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private let queue = DispatchQueue(label: "com.bridge.mac.server", qos: .userInitiated)
    
    private var crypto: BridgeCrypto?
    private var outgoingCounter: UInt64 = 0
    private var expectedIncomingCounter: UInt64 = 0
    
    private var heartbeatTimer: DispatchSourceTimer?
    private var lastActivityReceived: Date = Date()
    private var lastActivitySent: Date = Date()
    
    // In-flight incoming image assembly
    private var pendingImageStart: ClipboardImageStartMessage?
    private var receivedImageChunks: [UInt32: Data] = [:]
    
    // In-flight incoming file assembly
    private var pendingFileStart: FileStartMessage?
    private var receivedFileChunks: [UInt32: Data] = [:]
    
    // In-flight transfer cancellation
    private var isTransferCancelled: Bool = false
    private let transferCancelLock = NSLock()
    
    private init() {}
    
    public func start(key: Data) {
        queue.async { [weak self] in
            self?.startInternal(key: key)
        }
    }
    
    private func startInternal(key: Data) {
        if let existing = self.listener, existing.state == .ready {
            LogManager.shared.log("TCP Server already listening on port 48250")
            return
        }
        do {
            self.crypto = try BridgeCrypto(key: key)
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            tcpOptions.keepaliveIdle = 10
            tcpOptions.keepaliveInterval = 5
            tcpOptions.keepaliveCount = 3
            tcpOptions.noDelay = true
            
            let params = NWParameters(tls: nil, tcp: tcpOptions)
            params.allowLocalEndpointReuse = true
            params.includePeerToPeer = true
            params.serviceClass = .responsiveData
            
            let listener = try NWListener(using: params, on: self.port)
            let machineName = Host.current().localizedName ?? "Mac"
            listener.service = NWListener.Service(name: machineName, type: "_bridge._tcp")
            
            listener.newConnectionHandler = { [weak self] newConn in
                self?.handleNewConnection(newConn)
            }
            
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    LogManager.shared.log("TCP Server listening on port 48250 with mDNS _bridge._tcp")
                case .failed(let error):
                    LogManager.shared.log("TCP Server failed: \(error)", level: .error)
                    self?.stopInternal()
                case .cancelled:
                    LogManager.shared.log("TCP Server stopped")
                default:
                    break
                }
            }
            
            listener.start(queue: self.queue)
            self.listener = listener
        } catch {
            LogManager.shared.log("Failed to start TCP listener: \(error)", level: .error)
        }
    }
    
    public func restart(key: Data) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if let existing = self.listener, existing.state == .ready {
                LogManager.shared.log("TCP Server is already healthy on port 48250")
                return
            }
            self.stopInternal()
            self.queue.asyncAfter(deadline: .now() + .milliseconds(500)) { [weak self] in
                self?.startInternal(key: key)
            }
        }
    }
    
    public func stop() {
        queue.async { [weak self] in
            self?.stopInternal()
        }
    }
    
    public func disconnectClient() {
        queue.async { [weak self] in
            guard let self = self else { return }
            if let conn = self.activeConnection {
                LogManager.shared.log("User disconnected client connection")
                conn.cancel()
                self.cleanupConnection(conn)
            }
        }
    }
    
    private func stopInternal() {
        stopHeartbeat()
        activeConnection?.cancel()
        activeConnection = nil
        listener?.cancel()
        listener = nil
        delegate?.serverDidUpdateConnectionStatus(isConnected: false, clientDescription: nil)
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        queue.async { [weak self] in
            guard let self = self else { return }
            LogManager.shared.log("Incoming connection from \(connection.endpoint)")
            
            // If already connected, close the previous one cleanly
            if let existing = self.activeConnection {
                LogManager.shared.log("Replacing existing connection with new one")
                existing.cancel()
            }
            
            self.activeConnection = connection
            self.outgoingCounter = 0
            self.expectedIncomingCounter = 0
            self.lastActivityReceived = Date()
            self.lastActivitySent = Date()
            self.pendingImageStart = nil
            self.receivedImageChunks.removeAll()
            
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard let self = self, let connection = connection else { return }
                switch state {
                case .ready:
                    LogManager.shared.log("Client connected: \(connection.endpoint)")
                    self.startHeartbeat()
                    self.delegate?.serverDidUpdateConnectionStatus(isConnected: true, clientDescription: "\(connection.endpoint)")
                    self.readNextFrame(from: connection)
                case .failed(let error):
                    LogManager.shared.log("Connection failed: \(error)", level: .error)
                    self.cleanupConnection(connection)
                case .cancelled:
                    LogManager.shared.log("Connection cancelled")
                    self.cleanupConnection(connection)
                default:
                    break
                }
            }
            
            connection.start(queue: self.queue)
        }
    }
    
    private func cleanupConnection(_ connection: NWConnection) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if self.activeConnection === connection {
                self.stopHeartbeat()
                self.activeConnection = nil
                self.pendingImageStart = nil
                self.receivedImageChunks.removeAll()
                self.delegate?.serverDidUpdateConnectionStatus(isConnected: false, clientDescription: nil)
            }
        }
    }
    
    private func readNextFrame(from connection: NWConnection) {
        // Read 4-byte big-endian length prefix
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self, weak connection] content, _, isComplete, error in
            guard let self = self, let connection = connection else { return }
            if let error = error {
                LogManager.shared.log("Error reading frame length: \(error)", level: .error)
                self.cleanupConnection(connection)
                return
            }
            guard let lengthData = content, lengthData.count == 4 else {
                if isComplete {
                    self.cleanupConnection(connection)
                }
                return
            }
            
            let frameLength = lengthData.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            guard frameLength > 0 && frameLength <= 131072 else {
                LogManager.shared.log("Invalid frame length: \(frameLength), dropping connection", level: .error)
                connection.cancel()
                self.cleanupConnection(connection)
                return
            }
            
            self.readFrameBody(from: connection, length: Int(frameLength))
        }
    }
    
    private func readFrameBody(from connection: NWConnection, length: Int) {
        var accumulated = Data()
        accumulated.reserveCapacity(length)
        
        func receiveChunk() {
            let needed = length - accumulated.count
            connection.receive(minimumIncompleteLength: needed, maximumLength: needed) { [weak self, weak connection] content, _, isComplete, error in
                guard let self = self, let connection = connection else { return }
                if let error = error {
                    LogManager.shared.log("Error reading frame body: \(error)", level: .error)
                    self.cleanupConnection(connection)
                    return
                }
                if let chunk = content {
                    accumulated.append(chunk)
                }
                if accumulated.count < length {
                    if isComplete {
                        self.cleanupConnection(connection)
                    } else {
                        receiveChunk()
                    }
                } else {
                    self.processReceivedEncryptedFrame(accumulated, from: connection)
                    self.readNextFrame(from: connection)
                }
            }
        }
        receiveChunk()
    }
    
    private func processReceivedEncryptedFrame(_ ciphertextWithTag: Data, from connection: NWConnection) {
        guard let crypto = self.crypto else { return }
        
        let counter = self.expectedIncomingCounter
        do {
            let plaintext = try crypto.decrypt(
                ciphertextWithTag: ciphertextWithTag,
                counter: counter,
                direction: BridgeCrypto.directionAndroidToMac
            )
            // Successful decryption and auth: advance incoming counter
            self.expectedIncomingCounter += 1
            self.lastActivityReceived = Date()
            
            self.handlePlaintextMessage(plaintext)
        } catch {
            LogManager.shared.log("Decryption/Auth failed (counter \(counter)): silently dropping connection", level: .error)
            connection.cancel()
            self.cleanupConnection(connection)
        }
    }
    
    private func handlePlaintextMessage(_ data: Data) {
        guard !data.isEmpty else { return }
        let msgType = data[0]
        let payload = data.advanced(by: 1)
        
        switch msgType {
        case BridgeMessageType.ping.rawValue:
            // Send Pong reply
            send(type: .pong, value: PongMessage())
            
        case BridgeMessageType.pong.rawValue:
            // Heartbeat acknowledged
            break
            
        case BridgeMessageType.clipboardText.rawValue:
            if let msg = try? JSONDecoder().decode(ClipboardTextMessage.self, from: payload) {
                LogManager.shared.log("Received clipboard text from Android (\(msg.text.count) chars)")
                delegate?.serverDidReceiveText(text: msg.text, hash: msg.hash)
            } else {
                LogManager.shared.log("Failed to decode ClipboardTextMessage from Android", level: .error)
            }
            
        case BridgeMessageType.clipboardImageStart.rawValue:
            if let msg = try? JSONDecoder().decode(ClipboardImageStartMessage.self, from: payload) {
                self.pendingImageStart = msg
                self.receivedImageChunks.removeAll()
            }
            
        case BridgeMessageType.clipboardImageChunk.rawValue:
            if let chunk = ClipboardImageChunkData.deserialize(from: payload) {
                handleImageChunk(chunk)
            }
            
        case BridgeMessageType.clipboardImageAck.rawValue:
            if let ack = try? JSONDecoder().decode(ClipboardImageAckMessage.self, from: payload) {
                LogManager.shared.log("Image transfer acknowledged by phone: \(ack.status)")
            }
            
        case BridgeMessageType.fileStart.rawValue:
            if let msg = try? JSONDecoder().decode(FileStartMessage.self, from: payload) {
                LogManager.shared.log("Incoming file transfer: \(msg.name) (\(msg.size) bytes, \(msg.totalChunks) chunks)")
                self.pendingFileStart = msg
                self.receivedFileChunks.removeAll()
                self.delegate?.serverFileTransferProgress(transferId: msg.id, name: msg.name, progress: 0.0, isIncoming: true)
            }
            
        case BridgeMessageType.fileChunk.rawValue:
            if let chunk = FileChunkData.deserialize(from: payload) {
                handleFileChunk(chunk)
            }
            
        case BridgeMessageType.fileAck.rawValue:
            if let ack = try? JSONDecoder().decode(FileAckMessage.self, from: payload) {
                LogManager.shared.log("File transfer acknowledged by phone: \(ack.status)")
            }
            
        case BridgeMessageType.disconnect.rawValue:
            LogManager.shared.log("Client requested disconnect")
            if let conn = self.activeConnection {
                conn.cancel()
                self.cleanupConnection(conn)
            }
            
        case BridgeMessageType.notifPost.rawValue:
            if let msg = try? JSONDecoder().decode(NotifPostMessage.self, from: payload) {
                delegate?.serverDidReceiveNotificationPost(msg)
            } else {
                LogManager.shared.log("Failed to decode NotifPostMessage", level: .error)
            }
            
        case BridgeMessageType.notifRemove.rawValue:
            if let msg = try? JSONDecoder().decode(NotifRemoveMessage.self, from: payload) {
                delegate?.serverDidReceiveNotificationRemove(msg)
            } else {
                LogManager.shared.log("Failed to decode NotifRemoveMessage", level: .error)
            }
            
        case BridgeMessageType.iconResp.rawValue:
            if let msg = try? JSONDecoder().decode(IconRespMessage.self, from: payload) {
                delegate?.serverDidReceiveIconResponse(msg)
            } else {
                LogManager.shared.log("Failed to decode IconRespMessage", level: .error)
            }
            
        case BridgeMessageType.callEvent.rawValue:
            if let msg = try? JSONDecoder().decode(CallEventMessage.self, from: payload) {
                delegate?.serverDidReceiveCallEvent(msg)
            } else {
                LogManager.shared.log("Failed to decode CallEventMessage", level: .error)
            }
            
        case BridgeMessageType.lockMac.rawValue:
            LogManager.shared.log("Received remote lock command from Android")
            delegate?.serverDidReceiveLockRequest()
            
        default:
            LogManager.shared.log("Unknown message type: \(msgType)")
        }
    }
    
    private func handleFileChunk(_ chunk: FileChunkData) {
        guard let start = self.pendingFileStart, start.id == chunk.transferId else {
            return
        }
        
        receivedFileChunks[chunk.chunkIndex] = chunk.chunkBytes
        let currentCount = receivedFileChunks.count
        let total = start.totalChunks
        let throttleStep = max(1, total / 50)
        
        if currentCount == total || currentCount % throttleStep == 0 {
            let progress = Double(currentCount) / Double(total)
            delegate?.serverFileTransferProgress(transferId: start.id, name: start.name, progress: progress, isIncoming: true)
        }
        
        if currentCount == total {
            // Reassemble
            var fullData = Data()
            fullData.reserveCapacity(start.size)
            for idx in 0..<UInt32(total) {
                if let piece = receivedFileChunks[idx] {
                    fullData.append(piece)
                }
            }
            
            let computedHash = ImageProcessor.sha256Hex(of: fullData)
            if computedHash.lowercased() == start.hash.lowercased() {
                do {
                    let savedUrl = try FileManagerHelper.shared.saveReceivedFile(name: start.name, data: fullData)
                    send(type: .fileAck, value: FileAckMessage(id: start.id, status: "ok"))
                    delegate?.serverDidReceiveFile(name: start.name, savedUrl: savedUrl, size: fullData.count)
                    LogManager.shared.log("File \(start.name) received and saved successfully")
                } catch {
                    LogManager.shared.log("Failed to save received file: \(error)", level: .error)
                    send(type: .fileAck, value: FileAckMessage(id: start.id, status: "error"))
                }
            } else {
                LogManager.shared.log("File checksum mismatch! Expected \(start.hash), got \(computedHash)", level: .error)
                send(type: .fileAck, value: FileAckMessage(id: start.id, status: "error"))
            }
            self.pendingFileStart = nil
            self.receivedFileChunks.removeAll()
        }
    }
    
    public func cancelActiveTransfer() {
        transferCancelLock.lock()
        isTransferCancelled = true
        transferCancelLock.unlock()
        
        queue.async { [weak self] in
            guard let self = self else { return }
            if let pending = self.pendingFileStart {
                LogManager.shared.log("User cancelled incoming file transfer: \(pending.name)")
                self.send(type: .fileAck, value: FileAckMessage(id: pending.id, status: "error"))
                self.pendingFileStart = nil
                self.receivedFileChunks.removeAll()
            }
        }
    }
    
    public func sendFile(url: URL, progressHandler: @escaping (Double) -> Void, completion: @escaping (Result<Void, Error>) -> Void) {
        transferCancelLock.lock()
        isTransferCancelled = false
        transferCancelLock.unlock()
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                let fileData = try Data(contentsOf: url)
                let fileName = url.lastPathComponent
                let hash = ImageProcessor.sha256Hex(of: fileData)
                let chunks = ImageProcessor.chunkData(fileData)
                let totalChunks = chunks.count
                let transferId = UUID().uuidString.lowercased()
                
                let startMsg = FileStartMessage(
                    id: transferId,
                    origin: "mac",
                    name: fileName,
                    size: fileData.count,
                    mime: "application/octet-stream",
                    totalChunks: totalChunks,
                    hash: hash
                )
                
                self.send(type: .fileStart, value: startMsg)
                DispatchQueue.main.async {
                    self.delegate?.serverFileTransferProgress(transferId: transferId, name: fileName, progress: 0.0, isIncoming: false)
                }
                
                // Pipelined sliding window: 16 chunks (~1 MB) in flight to fully saturate Wi-Fi link
                let windowSemaphore = DispatchSemaphore(value: 16)
                let group = DispatchGroup()
                let throttleStep = max(1, totalChunks / 50)
                var sendError: Error?
                let errorLock = NSLock()
                
                for (index, chunk) in chunks.enumerated() {
                    self.transferCancelLock.lock()
                    let wasCancelled = self.isTransferCancelled
                    self.transferCancelLock.unlock()
                    if wasCancelled {
                        errorLock.lock()
                        sendError = NSError(
                            domain: "com.bridge.mac",
                            code: -999,
                            userInfo: [NSLocalizedDescriptionKey: "Transfer was cancelled by user"]
                        )
                        errorLock.unlock()
                        break
                    }
                    
                    errorLock.lock()
                    let hasError = sendError != nil
                    errorLock.unlock()
                    if hasError { break }
                    
                    windowSemaphore.wait()
                    guard let connection = self.activeConnection else {
                        windowSemaphore.signal()
                        errorLock.lock()
                        sendError = NSError(
                            domain: "com.bridge.mac",
                            code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Connection lost while transferring file"]
                        )
                        errorLock.unlock()
                        break
                    }
                    
                    let chunkObj = FileChunkData(
                        transferId: transferId,
                        chunkIndex: UInt32(index),
                        chunkBytes: chunk
                    )
                    
                    group.enter()
                    self.sendChunkPipelined(type: .fileChunk, payload: chunkObj.serialize(), on: connection) { error in
                        if let error = error {
                            errorLock.lock()
                            if sendError == nil { sendError = error }
                            errorLock.unlock()
                        }
                        windowSemaphore.signal()
                        group.leave()
                    }
                    
                    let current = index + 1
                    if current == totalChunks || current % throttleStep == 0 {
                        let progress = totalChunks > 0 ? (Double(current) / Double(totalChunks)) : 1.0
                        DispatchQueue.main.async {
                            progressHandler(progress)
                            self.delegate?.serverFileTransferProgress(transferId: transferId, name: fileName, progress: progress, isIncoming: false)
                        }
                    }
                }
                
                // Await drain of in-flight window
                group.wait()
                
                errorLock.lock()
                let finalError = sendError
                errorLock.unlock()
                
                DispatchQueue.main.async {
                    if let finalError = finalError {
                        completion(.failure(finalError))
                    } else {
                        progressHandler(1.0)
                        self.delegate?.serverFileTransferProgress(transferId: transferId, name: fileName, progress: 1.0, isIncoming: false)
                        completion(.success(()))
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }
    
    private func sendChunkPipelined(type: BridgeMessageType, payload: Data, on connection: NWConnection, completion: @escaping (Error?) -> Void) {
        guard let crypto = self.crypto else {
            completion(NSError(domain: "com.bridge.mac", code: -2, userInfo: [NSLocalizedDescriptionKey: "Crypto not initialized"]))
            return
        }
        
        queue.async { [weak self] in
            guard let self = self else {
                completion(NSError(domain: "com.bridge.mac", code: -3, userInfo: [NSLocalizedDescriptionKey: "Server deallocated"]))
                return
            }
            
            var plaintext = Data([type.rawValue])
            plaintext.append(payload)
            
            do {
                let ciphertext = try crypto.encrypt(
                    plaintext: plaintext,
                    counter: self.outgoingCounter,
                    direction: BridgeCrypto.directionMacToAndroid
                )
                self.outgoingCounter += 1
                self.lastActivitySent = Date()
                
                let frame = BridgeFrameHelper.makeFrame(ciphertext: ciphertext)
                connection.send(content: frame, completion: .contentProcessed({ error in
                    if let error = error {
                        LogManager.shared.log("Chunk send error: \(error)", level: .error)
                    }
                    completion(error)
                }))
            } catch {
                LogManager.shared.log("Chunk encryption failed: \(error)", level: .error)
                completion(error)
            }
        }
    }
    
    private func handleImageChunk(_ chunk: ClipboardImageChunkData) {
        guard let start = self.pendingImageStart, start.id.lowercased() == chunk.transferId.lowercased() else {
            return
        }
        
        receivedImageChunks[chunk.chunkIndex] = chunk.chunkBytes
        
        if receivedImageChunks.count == start.totalChunks {
            // Reassemble
            var fullData = Data()
            for idx in 0..<UInt32(start.totalChunks) {
                if let piece = receivedImageChunks[idx] {
                    fullData.append(piece)
                }
            }
            
            let computedHash = ImageProcessor.sha256Hex(of: fullData)
            if computedHash.lowercased() == start.hash.lowercased() {
                send(type: .clipboardImageAck, value: ClipboardImageAckMessage(id: start.id, status: "ok"))
                delegate?.serverDidReceiveImage(data: fullData, hash: computedHash)
            } else {
                LogManager.shared.log("Image checksum mismatch! Expected \(start.hash), got \(computedHash)", level: .error)
                send(type: .clipboardImageAck, value: ClipboardImageAckMessage(id: start.id, status: "error"))
            }
            self.pendingImageStart = nil
            self.receivedImageChunks.removeAll()
        }
    }
    
    public func send<T: Encodable>(type: BridgeMessageType, value: T) {
        guard let payload = try? JSONEncoder().encode(value) else { return }
        sendRaw(type: type, payload: payload)
    }
    
    public func sendRaw(type: BridgeMessageType, payload: Data) {
        queue.async { [weak self] in
            guard let self = self,
                  let connection = self.activeConnection,
                  let crypto = self.crypto else {
                return
            }
            
            var plaintext = Data([type.rawValue])
            plaintext.append(payload)
            
            do {
                let ciphertext = try crypto.encrypt(
                    plaintext: plaintext,
                    counter: self.outgoingCounter,
                    direction: BridgeCrypto.directionMacToAndroid
                )
                self.outgoingCounter += 1
                self.lastActivitySent = Date()
                
                let frame = BridgeFrameHelper.makeFrame(ciphertext: ciphertext)
                connection.send(content: frame, completion: .contentProcessed({ error in
                    if let error = error {
                        LogManager.shared.log("Send error: \(error)", level: .error)
                    }
                }))
            } catch {
                LogManager.shared.log("Encryption failed: \(error)", level: .error)
            }
        }
    }
    
    private func startHeartbeat() {
        stopHeartbeat()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .seconds(15), repeating: .seconds(15), leeway: .seconds(1))
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            let now = Date()
            
            // Check dead timeout (40 seconds of silence)
            if now.timeIntervalSince(self.lastActivityReceived) >= 40.0 {
                LogManager.shared.log("Connection dead (40s silence). Terminating.", level: .error)
                self.activeConnection?.cancel()
                if let conn = self.activeConnection {
                    self.cleanupConnection(conn)
                }
                return
            }
            
            // If idle for >= 15s since last send, ping
            if now.timeIntervalSince(self.lastActivitySent) >= 15.0 {
                self.send(type: .ping, value: PingMessage())
            }
        }
        timer.resume()
        self.heartbeatTimer = timer
    }
    
    private func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }
}
