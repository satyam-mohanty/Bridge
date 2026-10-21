package com.bridge.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bridge.android.util.LogManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bridge_secure_prefs"
        private const val KEY_ALIAS = "BridgeMasterKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_ENCRYPTED_KEY = "encrypted_shared_key"
        private const val PREF_KEY_IV = "key_iv"
        private const val PREF_MAC_NAME = "mac_name"
        private const val PREF_LAST_IP = "last_known_mac_ip"
        private const val PREF_AUTO_MODE = "auto_mode_enabled"
        private const val PREF_SEARCHING_ENABLED = "searching_enabled"
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
            LogManager.i("Generated new master key in Android Keystore")
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun saveSharedKey(keyBytes: ByteArray, macName: String? = null) {
        try {
            val masterKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val encryptedKey = cipher.doFinal(keyBytes)

            prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()

            if (macName != null) {
                prefs.edit().putString(PREF_MAC_NAME, macName).apply()
            }
            LogManager.i("Shared key securely encrypted and saved to Keystore preferences")
        } catch (e: Exception) {
            LogManager.e("Failed to encrypt and store shared key", e)
        }
    }

    fun loadSharedKey(): ByteArray? {
        val encryptedBase64 = prefs.getString(PREF_ENCRYPTED_KEY, null) ?: return null
        val ivBase64 = prefs.getString(PREF_KEY_IV, null) ?: return null

        return try {
            val encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val masterKey = getOrCreateMasterKey()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            LogManager.e("Failed to decrypt shared key from Keystore", e)
            null
        }
    }

    fun hasKey(): Boolean = loadSharedKey() != null

    fun getMacName(): String? = prefs.getString(PREF_MAC_NAME, null)

    fun saveLastKnownIp(ip: String) {
        prefs.edit().putString(PREF_LAST_IP, ip).apply()
        LogManager.i("Saved last known Mac IP: $ip")
    }

    fun getLastKnownIp(): String? = prefs.getString(PREF_LAST_IP, null)

    fun isAutoModeEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_MODE, true)

    fun setAutoModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_MODE, enabled).apply()
    }

    fun isSearchingEnabled(): Boolean = prefs.getBoolean(PREF_SEARCHING_ENABLED, true)

    fun setSearchingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_SEARCHING_ENABLED, enabled).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        LogManager.i("Cleared stored keys and pairing information")
    }
}
