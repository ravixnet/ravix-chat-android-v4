package net.ravix.chatoperator.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.ravix.chatoperator.BuildConfig
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("ravix_chat_operator", Context.MODE_PRIVATE)
    private val cipherBox = CipherBox()

    var baseUrl: String
        get() = prefs.getString("base_url", BuildConfig.DEFAULT_SERVER_URL) ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) { prefs.edit().putString("base_url", normalizeBaseUrl(value)).apply() }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) { prefs.edit().putString("username", value.trim()).apply() }

    var authToken: String
        get() = decryptPref("auth_token")
        set(value) = encryptPref("auth_token", value)

    var authCookie: String
        get() = decryptPref("auth_cookie")
        set(value) = encryptPref("auth_cookie", value)

    var socketUrl: String
        get() = prefs.getString("socket_url", baseUrl) ?: baseUrl
        set(value) { prefs.edit().putString("socket_url", normalizeBaseUrl(value)).apply() }

    var socketPath: String
        get() = prefs.getString("socket_path", BuildConfig.DEFAULT_SOCKET_PATH) ?: BuildConfig.DEFAULT_SOCKET_PATH
        set(value) { prefs.edit().putString("socket_path", normalizePath(value)).apply() }

    var keepOnline: Boolean
        get() = prefs.getBoolean("keep_online", true)
        set(value) { prefs.edit().putBoolean("keep_online", value).apply() }

    val isLoggedIn: Boolean
        get() = baseUrl.isNotBlank() && socketUrl.isNotBlank() && (authToken.isNotBlank() || authCookie.isNotBlank())

    fun clearLogin() {
        prefs.edit().clear().apply()
    }

    private fun encryptPref(key: String, value: String) {
        prefs.edit().putString(key, if (value.isBlank()) "" else cipherBox.encrypt(value)).apply()
    }

    private fun decryptPref(key: String): String {
        val encrypted = prefs.getString(key, "") ?: ""
        return if (encrypted.isBlank()) "" else cipherBox.decrypt(encrypted)
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return ""
            return "https://${uri.authority}${uri.path.orEmpty()}".trimEnd('/')
        }

        fun normalizePath(raw: String): String {
            val clean = raw.trim().ifBlank { BuildConfig.DEFAULT_SOCKET_PATH }
            return if (clean.startsWith('/')) clean else "/$clean"
        }
    }
}

private class CipherBox {
    private val alias = "ravix_chat_operator_session_key_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(value: String): String = runCatching {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
    }.getOrDefault("")
}
