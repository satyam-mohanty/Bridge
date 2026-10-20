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
    
    func testFileChunkSerialization() throws {
        let transferId = UUID().uuidString.lowercased()
        let chunkPayload = Data([0x01, 0x02, 0x03, 0x04, 0x05])
        let original = FileChunkData(transferId: transferId, chunkIndex: 7, chunkBytes: chunkPayload)
        
        let serialized = original.serialize()
        guard let parsed = FileChunkData.deserialize(from: serialized) else {
            XCTFail("Failed to deserialize file chunk")
            return
        }
        
        XCTAssertEqual(parsed.transferId, transferId)
        XCTAssertEqual(parsed.chunkIndex, 7)
        XCTAssertEqual(parsed.chunkBytes, chunkPayload)
    }
    
    func testNotificationMessageCodable() throws {
        let original = NotifPostMessage(
            key: "0|com.whatsapp|1234|tag",
            pkg: "com.whatsapp",
            app: "WhatsApp",
            title: "Alice",
            text: "Hello there!",
            bigText: "Hello there! Big text",
            subText: "Work",
            convTitle: "Team Chat",
            sender: "Alice",
            ts: 1729432000000,
            category: "msg",
            versionCode: 240901,
            largeIcon: "base64jpeg"
        )
        
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(NotifPostMessage.self, from: data)
        
        XCTAssertEqual(decoded.key, original.key)
        XCTAssertEqual(decoded.pkg, original.pkg)
        XCTAssertEqual(decoded.app, original.app)
        XCTAssertEqual(decoded.title, original.title)
        XCTAssertEqual(decoded.text, original.text)
        XCTAssertEqual(decoded.bigText, original.bigText)
        XCTAssertEqual(decoded.subText, original.subText)
        XCTAssertEqual(decoded.convTitle, original.convTitle)
        XCTAssertEqual(decoded.sender, original.sender)
        XCTAssertEqual(decoded.ts, original.ts)
        XCTAssertEqual(decoded.category, original.category)
        XCTAssertEqual(decoded.versionCode, original.versionCode)
        XCTAssertEqual(decoded.largeIcon, original.largeIcon)
        
        let need = IconNeedMessage(pkg: "com.whatsapp")
        let needData = try JSONEncoder().encode(need)
        let decodedNeed = try JSONDecoder().decode(IconNeedMessage.self, from: needData)
        XCTAssertEqual(decodedNeed.pkg, need.pkg)
        
        let resp = IconRespMessage(pkg: "com.whatsapp", versionCode: 240901, icon: "base64png")
        let respData = try JSONEncoder().encode(resp)
        let decodedResp = try JSONDecoder().decode(IconRespMessage.self, from: respData)
        XCTAssertEqual(decodedResp.pkg, resp.pkg)
        XCTAssertEqual(decodedResp.versionCode, resp.versionCode)
        XCTAssertEqual(decodedResp.icon, resp.icon)
        
        let remove = NotifRemoveMessage(key: "0|com.whatsapp|1234|tag")
        let removeData = try JSONEncoder().encode(remove)
        let decodedRemove = try JSONDecoder().decode(NotifRemoveMessage.self, from: removeData)
        XCTAssertEqual(decodedRemove.key, remove.key)
        
        let call = CallEventMessage(
            id: "+15551234567",
            state: "ringing",
            number: "+15551234567",
            name: "Jane Doe",
            ts: 1729432000000
        )
        let callData = try JSONEncoder().encode(call)
        let decodedCall = try JSONDecoder().decode(CallEventMessage.self, from: callData)
        XCTAssertEqual(decodedCall.id, call.id)
        XCTAssertEqual(decodedCall.state, call.state)
        XCTAssertEqual(decodedCall.number, call.number)
        XCTAssertEqual(decodedCall.name, call.name)
        XCTAssertEqual(decodedCall.ts, call.ts)
    }
    
    func testKeychainSaveAndLoadRoundtrip() throws {
        let originalService = KeychainHelper.service
        let originalAccount = KeychainHelper.account
        defer {
            KeychainHelper.service = originalService
            KeychainHelper.account = originalAccount
        }
        
        KeychainHelper.service = "com.bridge.mac.unittests"
        KeychainHelper.account = "UnitTestKey"
        KeychainHelper.deleteSharedKey()
        defer {
            KeychainHelper.deleteSharedKey()
        }
        
        let testKey = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        let saved = KeychainHelper.saveSharedKey(testKey)
        XCTAssertTrue(saved, "saveSharedKey should succeed")
        
        let loaded = KeychainHelper.loadSharedKey()
        XCTAssertEqual(loaded, testKey, "Loaded key must match saved key")
    }
    
    func testKeychainInvalidKeyLengthRejected() throws {
        let originalService = KeychainHelper.service
        let originalAccount = KeychainHelper.account
        defer {
            KeychainHelper.service = originalService
            KeychainHelper.account = originalAccount
        }
        
        KeychainHelper.service = "com.bridge.mac.unittests"
        KeychainHelper.account = "UnitTestInvalidKey"
        
        let invalidKey = Data([0x01, 0x02, 0x03]) // Only 3 bytes instead of 32
        let saved = KeychainHelper.saveSharedKey(invalidKey)
        XCTAssertFalse(saved, "Keys not 32 bytes must be rejected")
    }
    
    func testFallbackKeyStorage() throws {
        let originalService = KeychainHelper.service
        let originalAccount = KeychainHelper.account
        defer {
            KeychainHelper.service = originalService
            KeychainHelper.account = originalAccount
        }
        
        KeychainHelper.service = "com.bridge.mac.unittests"
        KeychainHelper.account = "UnitTestFallback"
        KeychainHelper.deleteFallbackKey()
        defer {
            KeychainHelper.deleteFallbackKey()
        }
        
        let testKey = Data((0..<32).map { UInt8($0) })
        KeychainHelper.saveFallbackKey(testKey)
        
        let loaded = KeychainHelper.loadFallbackKey()
        XCTAssertEqual(loaded, testKey)
        
        if let url = KeychainHelper.fallbackKeyURL {
            let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
            let posix = attrs[.posixPermissions] as? NSNumber
            XCTAssertEqual(posix?.intValue, 0o600, "Fallback key must have 0600 permissions")
        }
    }
}
