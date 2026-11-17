package dev.maxxing.github.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.maxxing.github.model.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidAuthContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requireContext(): Context =
        requireNotNull(appContext) { "AndroidAuthContextHolder.init(context) must be called before using TokenStore." }
}

private class AndroidKeystoreTokenStore(
    private val context: Context
) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { context.getSharedPreferences("maxxing_prefs", Context.MODE_PRIVATE) }
    private val sessionFile by lazy { File(context.filesDir, "maxxing_session.enc") }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override suspend fun saveSession(session: AuthSession) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(session).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val payload = "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        sessionFile.writeText(payload)
    }

    override suspend fun loadSession(): AuthSession? = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) return@withContext null
        return@withContext try {
            val parts = sessionFile.readText().split(':')
            if (parts.size != 2) return@withContext null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext).decodeToString()
            json.decodeFromString<AuthSession>(plaintext)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    override suspend fun saveClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
    }

    override suspend fun loadClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)

    override suspend fun saveThemePreferences(prefs: ThemePreferences) {
        this.prefs.edit().putString(KEY_THEME_PREFS, json.encodeToString(prefs)).apply()
    }

    override suspend fun loadThemePreferences(): ThemePreferences {
        val raw = prefs.getString(KEY_THEME_PREFS, null) ?: return ThemePreferences()
        return runCatching { json.decodeFromString<ThemePreferences>(raw) }.getOrDefault(ThemePreferences())
    }

    override suspend fun saveNotificationPreferences(prefs: NotificationPreferences) {
        this.prefs.edit().putString(KEY_NOTIF_PREFS, json.encodeToString(prefs)).apply()
    }

    override suspend fun loadNotificationPreferences(): NotificationPreferences {
        val raw = prefs.getString(KEY_NOTIF_PREFS, null) ?: return NotificationPreferences()
        return runCatching { json.decodeFromString<NotificationPreferences>(raw) }
            .getOrDefault(NotificationPreferences())
    }

    override suspend fun saveSeenNotificationMarkers(markers: Map<String, String>) {
        prefs.edit().putString(KEY_SEEN_NOTIFS, json.encodeToString(markers)).apply()
    }

    override suspend fun loadSeenNotificationMarkers(): Map<String, String> {
        val raw = prefs.getString(KEY_SEEN_NOTIFS, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    override suspend fun savePinnedRepos(repos: List<String>) {
        prefs.edit().putString(KEY_PINNED_REPOS, json.encodeToString(repos)).apply()
    }

    override suspend fun loadPinnedRepos(): List<String> {
        val raw = prefs.getString(KEY_PINNED_REPOS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "maxxing_session_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CLIENT_ID = "github_client_id"
        private const val KEY_THEME_PREFS = "theme_prefs"
        private const val KEY_NOTIF_PREFS = "notif_prefs"
        private const val KEY_SEEN_NOTIFS = "seen_notifs"
        private const val KEY_PINNED_REPOS = "pinned_repos"
    }
}

actual fun createPlatformTokenStore(): TokenStore =
    AndroidKeystoreTokenStore(AndroidAuthContextHolder.requireContext())

actual fun openUrlInBrowser(url: String): Boolean {
    return try {
        val context = AndroidAuthContextHolder.requireContext()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

actual fun shareText(text: String, title: String?): Boolean {
    return try {
        val context = AndroidAuthContextHolder.requireContext()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (_: Exception) {
        false
    }
}
