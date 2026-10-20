import Foundation
import Security

public enum KeychainHelper {
    private static let service = "com.bridge.mac"
    private static let account = "BridgeSharedKey"
    
    public static func getOrCreateSharedKey() -> Data {
        if let existing = loadSharedKey() {
            return existing
        }
        
        var randomBytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, randomBytes.count, &randomBytes)
        let newKey = status == errSecSuccess ? Data(randomBytes) : Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        
        _ = saveSharedKey(newKey)
        LogManager.shared.log("Generated fresh 32-byte pairing key and saved to Keychain")
        return newKey
    }
    
    public static func loadSharedKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data, data.count == 32 {
            return data
        }
        return nil
    }
    
    public static func saveSharedKey(_ key: Data) -> Bool {
        guard key.count == 32 else { return false }
        
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        return status == errSecSuccess
    }
}
