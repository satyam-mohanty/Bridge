import Foundation
import Security

public enum KeychainHelper {
    public static var service = "com.bridge.mac"
    public static var account = "BridgeSharedKey"
    
    /// Creates an Access Control List (ACL) that explicitly trusts any application instance
    /// without prompting the user. This avoids recurring "login keychain password" dialogs
    /// on macOS when using ad-hoc code signing across restarts and recompilations.
    private static func createPermissiveAccess(description: String) -> SecAccess? {
        var access: SecAccess?
        let status = SecAccessCreate(description as CFString, nil, &access)
        guard status == errSecSuccess, let access = access else {
            return nil
        }
        
        var aclList: CFArray?
        if SecAccessCopyACLList(access, &aclList) == errSecSuccess, let acls = aclList as? [SecACL] {
            for acl in acls {
                SecACLSetContents(acl, nil, description as CFString, SecKeychainPromptSelector())
            }
        }
        return access
    }
    
    internal static var fallbackKeyURL: URL? {
        guard let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = appSupport.appendingPathComponent("Bridge", isDirectory: true)
        let fileName = service == "com.bridge.mac" ? "pairing.key" : "\(service)_\(account).key"
        return dir.appendingPathComponent(fileName)
    }
    
    internal static func saveFallbackKey(_ key: Data) {
        guard let url = fallbackKeyURL else { return }
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [
            .posixPermissions: 0o700
        ])
        try? key.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    
    internal static func loadFallbackKey() -> Data? {
        guard let url = fallbackKeyURL,
              let data = try? Data(contentsOf: url),
              data.count == 32 else {
            return nil
        }
        return data
    }
    
    internal static func deleteFallbackKey() {
        guard let url = fallbackKeyURL else { return }
        try? FileManager.default.removeItem(at: url)
    }
    
    public static func getOrCreateSharedKey() -> Data {
        if let existing = loadSharedKey() {
            // Ensure existing keys created prior to permissive ACL migration are re-saved
            // with permissive ACL and mirrored to secure storage so macOS never prompts again.
            let migrationKey = "keychain_acl_migrated_v2"
            if !UserDefaults.standard.bool(forKey: migrationKey) {
                if saveSharedKey(existing) {
                    UserDefaults.standard.set(true, forKey: migrationKey)
                    LogManager.shared.log("Migrated existing Keychain item to permissive access control and secure storage")
                }
            }
            return existing
        }
        
        var randomBytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, randomBytes.count, &randomBytes)
        let newKey = status == errSecSuccess ? Data(randomBytes) : Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        
        _ = saveSharedKey(newKey)
        UserDefaults.standard.set(true, forKey: "keychain_acl_migrated_v2")
        LogManager.shared.log("Generated fresh 32-byte pairing key and saved to secure storage with permissive access")
        return newKey
    }
    
    public static func loadSharedKey() -> Data? {
        // Priority 1: Check user-restricted fallback storage (0600 permissions)
        // for instant, prompt-free retrieval across any ad-hoc builds.
        if let fallback = loadFallbackKey() {
            return fallback
        }
        
        // Priority 2: Check macOS Keychain
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
            saveFallbackKey(data)
            return data
        }
        
        return nil
    }
    
    public static func saveSharedKey(_ key: Data) -> Bool {
        guard key.count == 32 else { return false }
        
        // Always mirror to user-restricted fallback storage (0600 permissions)
        saveFallbackKey(key)
        
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        
        var addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        
        if let access = createPermissiveAccess(description: "Bridge Shared Key") {
            addQuery[kSecAttrAccess as String] = access
        }
        
        var status = SecItemAdd(addQuery as CFDictionary, nil)
        if status != errSecSuccess && addQuery[kSecAttrAccess as String] != nil {
            // Fallback without kSecAttrAccess if rejected by environment
            addQuery.removeValue(forKey: kSecAttrAccess as String)
            status = SecItemAdd(addQuery as CFDictionary, nil)
        }
        
        return status == errSecSuccess
    }
    
    public static func deleteSharedKey() {
        deleteFallbackKey()
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(deleteQuery as CFDictionary)
    }
}
