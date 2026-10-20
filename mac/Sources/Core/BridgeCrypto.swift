import Foundation
import CryptoKit

public enum BridgeCryptoError: Error, LocalizedError {
    case invalidKeyLength
    case invalidNonce
    case invalidCiphertextLength
    case replayDetected(received: UInt64, expected: UInt64)
    case decryptionFailed
    
    public var errorDescription: String? {
        switch self {
        case .invalidKeyLength:
            return "Key must be exactly 32 bytes."
        case .invalidNonce:
            return "Failed to construct valid 12-byte nonce."
        case .invalidCiphertextLength:
            return "Ciphertext must be at least 16 bytes for GCM tag."
        case let .replayDetected(received, expected):
            return "Replay attack detected: received counter \(received), expected \(expected)."
        case .decryptionFailed:
            return "Decryption or tag authentication failed."
        }
    }
}

public final class BridgeCrypto {
    public static let directionMacToAndroid: UInt8 = 0x01
    public static let directionAndroidToMac: UInt8 = 0x02
    public static let tagLength: Int = 16
    
    private let symmetricKey: SymmetricKey
    
    public init(key: Data) throws {
        guard key.count == 32 else {
            throw BridgeCryptoError.invalidKeyLength
        }
        self.symmetricKey = SymmetricKey(data: key)
    }
    
    public static func buildNonce(direction: UInt8, counter: UInt64) throws -> AES.GCM.Nonce {
        var nonceData = Data([direction, 0x00, 0x00, 0x00])
        var counterBigEndian = counter.bigEndian
        withUnsafeBytes(of: &counterBigEndian) { bytes in
            nonceData.append(contentsOf: bytes)
        }
        guard nonceData.count == 12 else {
            throw BridgeCryptoError.invalidNonce
        }
        return try AES.GCM.Nonce(data: nonceData)
    }
    
    public func encrypt(plaintext: Data, counter: UInt64, direction: UInt8) throws -> Data {
        let nonce = try Self.buildNonce(direction: direction, counter: counter)
        let sealed = try AES.GCM.seal(plaintext, using: symmetricKey, nonce: nonce)
        guard let combined = sealed.combined else {
            return sealed.ciphertext + sealed.tag
        }
        // CryptoKit combined is [nonce (12)] + [ciphertext] + [tag (16)]
        // Our protocol wire body is [ciphertext] + [tag (16)], since nonce is derived deterministically from (direction + counter)
        return sealed.ciphertext + sealed.tag
    }
    
    public func decrypt(ciphertextWithTag: Data, counter: UInt64, direction: UInt8) throws -> Data {
        guard ciphertextWithTag.count >= Self.tagLength else {
            throw BridgeCryptoError.invalidCiphertextLength
        }
        let nonce = try Self.buildNonce(direction: direction, counter: counter)
        let ciphertext = ciphertextWithTag.prefix(ciphertextWithTag.count - Self.tagLength)
        let tag = ciphertextWithTag.suffix(Self.tagLength)
        
        do {
            let sealed = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(sealed, using: symmetricKey)
        } catch {
            throw BridgeCryptoError.decryptionFailed
        }
    }
}
