package au.com.chrismckechnie.hermesmobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HostLoadResult(
    val snapshot: HostSnapshot,
    val unlockFailed: Boolean = false,
)

interface HostStore {
    fun load(): HostLoadResult
    fun save(snapshot: HostSnapshot)
}

/** Pure JSON codec for [HostSnapshot], kept crypto-free so it is unit-testable. */
object HostSnapshotCodec {
    fun encode(snapshot: HostSnapshot): String = JSONObject().apply {
        put("selectedHostId", snapshot.selectedHostId ?: JSONObject.NULL)
        put("hosts", JSONArray().apply {
            snapshot.hosts.forEach { host ->
                put(JSONObject().apply {
                    put("id", host.id)
                    put("name", host.name)
                    put("baseUrl", host.baseUrl)
                    put("apiKey", host.apiKey)
                    put("allowInsecureHttp", host.allowInsecureHttp)
                })
            }
        })
    }.toString()

    fun decode(raw: String): HostSnapshot {
        val json = JSONObject(raw)
        val hostsJson = json.optJSONArray("hosts") ?: JSONArray()
        val hosts = buildList {
            for (index in 0 until hostsJson.length()) {
                val item = hostsJson.optJSONObject(index) ?: continue
                runCatching {
                    HostProfile(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        baseUrl = item.getString("baseUrl"),
                        apiKey = item.getString("apiKey"),
                        allowInsecureHttp = item.optBoolean("allowInsecureHttp", false),
                    )
                }.getOrNull()?.let(::add)
            }
        }
        val selected = json.opt("selectedHostId")?.takeUnless { it == JSONObject.NULL }?.toString()
        return HostSnapshot(hosts = hosts, selectedHostId = selected)
    }
}

/** Plain (unencrypted) SharedPreferences store for non-sensitive app settings. */
class PreferencesSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences("hermes_mobile_settings", Context.MODE_PRIVATE)

    override fun loadThemeMode(): ThemeMode =
        preferences.getString("theme_mode", null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.System

    override fun saveThemeMode(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
    }

    override fun loadRunCheckpoint(): RunCheckpoint? {
        val hostId = preferences.getString("run_host_id", null) ?: return null
        val sessionId = preferences.getString("run_session_id", null) ?: return null
        val runId = preferences.getString("run_id", null) ?: return null
        return RunCheckpoint(hostId, sessionId, runId)
    }

    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) {
        // commit() is intentional: once submitRun returns, the checkpoint must
        // be durable before Android can tear down the Activity/process.
        preferences.edit()
            .putString("run_host_id", checkpoint.hostId)
            .putString("run_session_id", checkpoint.sessionId)
            .putString("run_id", checkpoint.runId)
            .commit()
    }

    override fun clearRunCheckpoint() {
        preferences.edit()
            .remove("run_host_id")
            .remove("run_session_id")
            .remove("run_id")
            .apply()
    }

    override fun loadRunStatus(runId: String): String? =
        preferences.getString("run_status.$runId", null)

    override fun saveRunStatus(runId: String, status: String) {
        preferences.edit().putString("run_status.$runId", status.take(240)).apply()
    }

    override fun clearRunStatus(runId: String) {
        preferences.edit().remove("run_status.$runId").apply()
    }

    override fun loadNotificationHostIds(): Set<String> =
        preferences.getStringSet("notification_host_ids", emptySet()).orEmpty().toSet()

    override fun saveNotificationHostIds(hostIds: Set<String>) {
        preferences.edit().putStringSet("notification_host_ids", hostIds.toSet()).apply()
    }

    override fun loadOverlayEnabled(): Boolean = preferences.getBoolean("overlay_enabled", false)

    override fun saveOverlayEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("overlay_enabled", enabled).apply()
    }

    override fun loadInstallationId(): String? = preferences.getString("installation_id", null)

    override fun getOrCreateInstallationId(): String = loadInstallationId() ?: java.util.UUID.randomUUID().toString().also {
        preferences.edit().putString("installation_id", it).commit()
    }
}

class SecureHostStore(context: Context) : HostStore {
    private val preferences = context.getSharedPreferences("hermes_mobile_secure_hosts", Context.MODE_PRIVATE)
    private val alias = "hermes-mobile-hosts-v1"

    override fun load(): HostLoadResult {
        val payload = preferences.getString("encrypted_snapshot", null) ?: return HostLoadResult(HostSnapshot())
        return runCatching { HostLoadResult(HostSnapshotCodec.decode(decrypt(payload))) }
            .getOrElse { HostLoadResult(HostSnapshot(), unlockFailed = true) }
    }

    override fun save(snapshot: HostSnapshot) {
        preferences.edit().putString("encrypted_snapshot", encrypt(HostSnapshotCodec.encode(snapshot))).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(4 + cipher.iv.size + cipherText.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(cipherText)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(data)
        val ivLength = buffer.int
        require(ivLength in 12..32 && ivLength <= buffer.remaining()) { "Invalid encrypted host data." }
        val iv = ByteArray(ivLength).also(buffer::get)
        val cipherText = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
