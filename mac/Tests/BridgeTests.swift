import XCTest
@testable import Bridge

final class BridgeTests: XCTestCase {
    
    func testCryptoWithPrecomputedVectors() throws {
        // Key: 32 bytes of 0x42
        let keyBase64 = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
        guard let keyData = Data(base64Encoded: keyBase64) else {
            XCTFail("Failed to decode base64 key")
            return
        }
        
        let crypto = try BridgeCrypto(key: keyData)
        
        // Vector 1: Mac -> Android, counter 1
        let ciphertext1Base64 = "1LjwdKgHynGYtX/zQOr+w+9294txFBf4p2NW5p7iQGYjwiS9YR7ZXeo8fgyiJvl0cg=="
        guard let cipherData1 = Data(base64Encoded: ciphertext1Base64) else {
            XCTFail("Failed to decode vector 1 ciphertext")
            return
        }
        
        let decrypted1 = try crypto.decrypt(
            ciphertextWithTag: cipherData1,
            counter: 1,
            direction: BridgeCrypto.directionMacToAndroid
        )
        let plain1 = String(data: decrypted1, encoding: .utf8)
        XCTAssertEqual(plain1, "Hello Bridge Security Test Vector")
        
        // Vector 2: Android -> Mac, counter 42
        let ciphertext2Base64 = "KsalvSlI/yU5Sukg9+tluipRbye65ymDXbV7SpD3P9PcE4bdILqY/GCW"
        guard let cipherData2 = Data(base64Encoded: ciphertext2Base64) else {
            XCTFail("Failed to decode vector 2 ciphertext")
            return
        }
        
        let decrypted2 = try crypto.decrypt(
            ciphertextWithTag: cipherData2,
            counter: 42,
            direction: BridgeCrypto.directionAndroidToMac
        )
        let plain2 = String(data: decrypted2, encoding: .utf8)
        XCTAssertEqual(plain2, "Android to Mac Bridge Ping")
    }
    
    func testCryptoRoundTrip() throws {
        let keyData = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        let crypto = try BridgeCrypto(key: keyData)
        
        let message = "Testing dynamic AES-256-GCM round trip message with emojis 🚀📱💻"
        let plaintext = message.data(using: .utf8)!
        let counter: UInt64 = 100
        
        let encrypted = try crypto.encrypt(
            plaintext: plaintext,
            counter: counter,
            direction: BridgeCrypto.directionMacToAndroid
        )
        
        let decrypted = try crypto.decrypt(
            ciphertextWithTag: encrypted,
            counter: counter,
            direction: BridgeCrypto.directionMacToAndroid
        )
        
        XCTAssertEqual(String(data: decrypted, encoding: .utf8), message)
    }
    
    func testReplayAndBadTagRejection() throws {
        let keyData = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        let crypto = try BridgeCrypto(key: keyData)
        
        let plaintext = "Sensitive data".data(using: .utf8)!
        let encrypted = try crypto.encrypt(
            plaintext: plaintext,
            counter: 5,
            direction: BridgeCrypto.directionMacToAndroid
        )
        
        // Wrong counter (replay simulation)
        XCTAssertThrowsError(try crypto.decrypt(
            ciphertextWithTag: encrypted,
            counter: 4, // wrong counter!
            direction: BridgeCrypto.directionMacToAndroid
        ))
        
        // Wrong direction
        XCTAssertThrowsError(try crypto.decrypt(
            ciphertextWithTag: encrypted,
            counter: 5,
            direction: BridgeCrypto.directionAndroidToMac // wrong direction!
        ))
        
        // Corrupted tag
        var tampered = encrypted
        tampered[tampered.count - 1] ^= 0xFF
        XCTAssertThrowsError(try crypto.decrypt(
            ciphertextWithTag: tampered,
            counter: 5,
            direction: BridgeCrypto.directionMacToAndroid
        ))
    }
    
    func testImageChunkingAndSha256() throws {
        // Generate 150 KB test data
        let testData = Data((0..<153600).map { UInt8($0 % 256) })
        let expectedHash = ImageProcessor.sha256Hex(of: testData)
        
        let chunks = ImageProcessor.chunkData(testData)
        XCTAssertEqual(chunks.count, 3) // 64K + 64K + 22K
        
        var reassembled = Data()
        for chunk in chunks {
            reassembled.append(chunk)
        }
        
        XCTAssertEqual(reassembled.count, testData.count)
        let reassembledHash = ImageProcessor.sha256Hex(of: reassembled)
        XCTAssertEqual(reassembledHash, expectedHash)
    }
    
    func testChunkSerialization() throws {
        let transferId = UUID().uuidString.lowercased()
        let chunkPayload = Data([0xDE, 0xAD, 0xBE, 0xEF])
        let original = ClipboardImageChunkData(transferId: transferId, chunkIndex: 3, chunkBytes: chunkPayload)
        
        let serialized = original.serialize()
        guard let parsed = ClipboardImageChunkData.deserialize(from: serialized) else {
            XCTFail("Failed to deserialize chunk")
            return
        }
        
        XCTAssertEqual(parsed.transferId, transferId)
        XCTAssertEqual(parsed.chunkIndex, 3)
        XCTAssertEqual(parsed.chunkBytes, chunkPayload)
    }
}
