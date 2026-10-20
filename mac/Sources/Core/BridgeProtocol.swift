import Foundation

public enum BridgeMessageType: UInt8 {
    case ping = 0x01
    case pong = 0x02
    case clipboardText = 0x03
    case clipboardImageStart = 0x04
    case clipboardImageChunk = 0x05
    case clipboardImageAck = 0x06
    case disconnect = 0x07
    case fileStart = 0x08
    case fileChunk = 0x09
    case fileAck = 0x0A
}

public struct PingMessage: Codable {
    public let t: Int64
    public init(t: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.t = t
    }
}

public struct PongMessage: Codable {
    public let t: Int64
    public init(t: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.t = t
    }
}

public struct ClipboardTextMessage: Codable {
    public let id: String
    public let origin: String
    public let hash: String
    public let text: String
    
    public init(id: String = UUID().uuidString.lowercased(), origin: String, hash: String, text: String) {
        self.id = id
        self.origin = origin
        self.hash = hash
        self.text = text
    }
}

public struct ClipboardImageStartMessage: Codable {
    public let id: String
    public let origin: String
    public let hash: String
    public let mime: String
    public let size: Int
    public let totalChunks: Int
    
    public init(id: String = UUID().uuidString.lowercased(), origin: String, hash: String, mime: String, size: Int, totalChunks: Int) {
        self.id = id
        self.origin = origin
        self.hash = hash
        self.mime = mime
        self.size = size
        self.totalChunks = totalChunks
    }
}

public struct ClipboardImageChunkData {
    public let transferId: String
    public let chunkIndex: UInt32
    public let chunkLength: UInt32
    public let chunkBytes: Data
    
    public init(transferId: String, chunkIndex: UInt32, chunkBytes: Data) {
        self.transferId = transferId
        self.chunkIndex = chunkIndex
        self.chunkLength = UInt32(chunkBytes.count)
        self.chunkBytes = chunkBytes
    }
    
    public func serialize() -> Data {
        var data = Data()
        // UUID is 36 ASCII characters
        let idData = transferId.utf8
        if idData.count == 36 {
            data.append(contentsOf: idData)
        } else {
            // pad or slice to 36 bytes
            var padded = [UInt8](repeating: 0x20, count: 36)
            for (idx, b) in idData.prefix(36).enumerated() {
                padded[idx] = b
            }
            data.append(contentsOf: padded)
        }
        var beIndex = chunkIndex.bigEndian
        withUnsafeBytes(of: &beIndex) { data.append(contentsOf: $0) }
        var beLen = chunkLength.bigEndian
        withUnsafeBytes(of: &beLen) { data.append(contentsOf: $0) }
        data.append(chunkBytes)
        return data
    }
    
    public static func deserialize(from data: Data) -> ClipboardImageChunkData? {
        guard data.count >= 44 else { return nil }
        guard let idString = String(data: data.prefix(36), encoding: .utf8)?.trimmingCharacters(in: .whitespaces) else {
            return nil
        }
        let indexBytes = data.subdata(in: 36..<40)
        let lenBytes = data.subdata(in: 40..<44)
        
        let chunkIndex = indexBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }
        let chunkLen = lenBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }
        
        guard data.count >= 44 + Int(chunkLen) else { return nil }
        let chunkBytes = data.subdata(in: 44..<(44 + Int(chunkLen)))
        return ClipboardImageChunkData(transferId: idString, chunkIndex: chunkIndex, chunkBytes: chunkBytes)
    }
}

public struct ClipboardImageAckMessage: Codable {
    public let id: String
    public let status: String
    
    public init(id: String, status: String) {
        self.id = id
        self.status = status
    }
}

public struct FileStartMessage: Codable {
    public let id: String
    public let origin: String
    public let name: String
    public let size: Int
    public let mime: String
    public let totalChunks: Int
    public let hash: String
    
    public init(id: String = UUID().uuidString.lowercased(), origin: String, name: String, size: Int, mime: String, totalChunks: Int, hash: String) {
        self.id = id
        self.origin = origin
        self.name = name
        self.size = size
        self.mime = mime
        self.totalChunks = totalChunks
        self.hash = hash
    }
}

public struct FileChunkData {
    public let transferId: String
    public let chunkIndex: UInt32
    public let chunkLength: UInt32
    public let chunkBytes: Data
    
    public init(transferId: String, chunkIndex: UInt32, chunkBytes: Data) {
        self.transferId = transferId
        self.chunkIndex = chunkIndex
        self.chunkLength = UInt32(chunkBytes.count)
        self.chunkBytes = chunkBytes
    }
    
    public func serialize() -> Data {
        var data = Data()
        let idData = transferId.utf8
        if idData.count == 36 {
            data.append(contentsOf: idData)
        } else {
            var padded = [UInt8](repeating: 0x20, count: 36)
            for (idx, b) in idData.prefix(36).enumerated() {
                padded[idx] = b
            }
            data.append(contentsOf: padded)
        }
        var beIndex = chunkIndex.bigEndian
        withUnsafeBytes(of: &beIndex) { data.append(contentsOf: $0) }
        var beLen = chunkLength.bigEndian
        withUnsafeBytes(of: &beLen) { data.append(contentsOf: $0) }
        data.append(chunkBytes)
        return data
    }
    
    public static func deserialize(from data: Data) -> FileChunkData? {
        guard data.count >= 44 else { return nil }
        guard let idString = String(data: data.prefix(36), encoding: .utf8)?.trimmingCharacters(in: .whitespaces) else {
            return nil
        }
        let indexBytes = data.subdata(in: 36..<40)
        let lenBytes = data.subdata(in: 40..<44)
        
        let chunkIndex = indexBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }
        let chunkLen = lenBytes.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian }
        
        guard data.count >= 44 + Int(chunkLen) else { return nil }
        let chunkBytes = data.subdata(in: 44..<(44 + Int(chunkLen)))
        return FileChunkData(transferId: idString, chunkIndex: chunkIndex, chunkBytes: chunkBytes)
    }
}

public struct FileAckMessage: Codable {
    public let id: String
    public let status: String
    
    public init(id: String, status: String) {
        self.id = id
        self.status = status
    }
}

public struct DisconnectMessage: Codable {
    public let reason: String
    public init(reason: String) {
        self.reason = reason
    }
}

public struct PairingInfo: Codable {
    public let v: Int
    public let name: String
    public let key: String
    public let port: Int
    
    public init(v: Int = 1, name: String, key: String, port: Int = 48250) {
        self.v = v
        self.name = name
        self.key = key
        self.port = port
    }
}

public struct BeaconInfo: Codable {
    public let v: Int
    public let name: String
    public let port: Int
    
    public init(v: Int = 1, name: String, port: Int = 48250) {
        self.v = v
        self.name = name
        self.port = port
    }
}

public enum BridgeFrameHelper {
    public static func makeFrame(ciphertext: Data) -> Data {
        var frame = Data()
        var len = UInt32(ciphertext.count).bigEndian
        withUnsafeBytes(of: &len) { frame.append(contentsOf: $0) }
        frame.append(ciphertext)
        return frame
    }
    
    public static func encodeMessage<T: Encodable>(type: BridgeMessageType, value: T) throws -> Data {
        var data = Data([type.rawValue])
        let jsonData = try JSONEncoder().encode(value)
        data.append(jsonData)
        return data
    }
    
    public static func encodeRawMessage(type: BridgeMessageType, rawPayload: Data) -> Data {
        var data = Data([type.rawValue])
        data.append(rawPayload)
        return data
    }
}
