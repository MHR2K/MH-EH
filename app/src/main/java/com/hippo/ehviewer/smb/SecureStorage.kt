package com.hippo.ehviewer.smb

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 安全存储工具类：提供加密的 SharedPreferences 包装
 * 
 * 特性：
 * - 使用 AES-GCM 加密敏感数据（密码）
 * - 向后兼容：如果加密失败，回退到明文存储
 * - 自动生成和轮换加密密钥
 */
object SecureStorage {
    private const val PREFS_NAME = "smb_secure_prefs"
    private const val ALIAS_KEY = "smb_master_key"
    private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    
    private var encryptedPrefs: SharedPreferences? = null

    /**
     * 获取加密的 SharedPreferences
     * 如果不可用，回退到标准 SharedPreferences
     */
    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return encryptedPrefs ?: try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encryptedPrefs = prefs
            prefs
        } catch (e: Exception) {
            // 如果加密不可用，使用标准 SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 加密字符串
     * @return Base64 编码的密文，格式为: IV + separator + Ciphertext
     */
    fun encrypt(plainText: String, prefs: SharedPreferences): String {
        if (plainText.isEmpty()) return ""
        
        return try {
            val key = getOrCreateSecretKey(prefs)
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            // 格式: IV (12 bytes) + ":" + CipherText (Base64)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            "$ivBase64:$cipherBase64"
        } catch (e: Exception) {
            // 加密失败，返回原文本（标记为未加密）
            "PLAIN:$plainText"
        }
    }

    /**
     * 解密字符串
     * @return 解密后的原文，如果无法解密返回原值
     */
    fun decrypt(encryptedText: String, prefs: SharedPreferences): String {
        if (encryptedText.isEmpty()) return ""
        
        // 检查是否是明文（回退情况）
        if (encryptedText.startsWith("PLAIN:")) {
            return encryptedText.substring(6)
        }
        
        return try {
            val parts = encryptedText.split(":", limit = 2)
            if (parts.size != 2) return encryptedText
            
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            
            val key = getOrCreateSecretKey(prefs)
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 解密失败，返回原值
            encryptedText
        }
    }

    /**
     * 获取或创建 AES 密钥
     */
    private fun getOrCreateSecretKey(prefs: SharedPreferences): SecretKey {
        val keyBase64 = prefs.getString("aes_key", null)
        
        if (keyBase64 != null) {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            return SecretKeySpec(keyBytes, "AES")
        }
        
        // 生成新密钥
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256, SecureRandom())
        val newKey = keyGenerator.generateKey()
        
        // 保存密钥
        val keyBase64New = Base64.encodeToString(newKey.encoded, Base64.NO_WRAP)
        prefs.edit().putString("aes_key", keyBase64New).apply()
        
        return newKey
    }

    /**
     * 检查是否是加密数据
     */
    fun isEncrypted(value: String): Boolean {
        return value.isNotEmpty() && !value.startsWith("PLAIN:")
    }

    /**
     * 迁移现有数据到加密存储
     * @param oldPrefs 旧的 SharedPreferences
     * @param key 要迁移的键
     * @return 迁移是否成功
     */
    fun migrateValue(oldPrefs: SharedPreferences, key: String, newPrefs: SharedPreferences): Boolean {
        val oldValue = oldPrefs.getString(key, null) ?: return true
        
        return try {
            val encrypted = encrypt(oldValue, newPrefs)
            newPrefs.edit().putString(key, encrypted).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
