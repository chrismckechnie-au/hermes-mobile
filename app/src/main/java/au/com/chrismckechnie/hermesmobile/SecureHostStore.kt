package au.com.chrismckechnie.hermesmobile

import android.annotation.SuppressLint
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

object MobileRegistrationStatusCodec {
    fun encode(statuses: List<MobileRegistrationStatus>): String = JSONArray().apply {
        statuses.distinctBy(MobileRegistrationStatus::hostId).forEach { status ->
            put(JSONObject().apply {
                put("hostId", status.hostId)
                put("desired", status.desired)
                put("registered", status.registered)
                put("pending", status.pending)
                put("errorMessage", status.errorMessage ?: JSONObject.NULL)
                put("lastSuccessAtMillis", status.lastSuccessAtMillis ?: JSONObject.NULL)
                put("lastSuccessMessage", status.lastSuccessMessage ?: JSONObject.NULL)
                put("lastFailureAtMillis", status.lastFailureAtMillis ?: JSONObject.NULL)
                put("lastFailureMessage", status.lastFailureMessage ?: JSONObject.NULL)
            })
        }
    }.toString()

    fun decode(raw: String): List<MobileRegistrationStatus> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hostId = item.optString("hostId")
                if (hostId.isBlank()) continue
                add(
                    MobileRegistrationStatus(
                        hostId = hostId,
                        desired = item.optBoolean("desired", false),
                        registered = item.optBoolean("registered", false),
                        pending = item.optBoolean("pending", false),
                        errorMessage = item.opt("errorMessage")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            ?.takeIf(String::isNotBlank),
                        lastSuccessAtMillis = item.optLongOrNull("lastSuccessAtMillis"),
                        lastSuccessMessage = item.optNullableString("lastSuccessMessage"),
                        lastFailureAtMillis = item.optLongOrNull("lastFailureAtMillis"),
                        lastFailureMessage = item.optNullableString("lastFailureMessage"),
                    ),
                )
            }
        }.distinctBy(MobileRegistrationStatus::hostId)
    }.getOrDefault(emptyList())
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optNullableString(name: String): String? =
    opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)

private val mobileRegistrationStatusLock = Any()

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

/** Pure codec so queued follow-up migrations stay JVM-testable. */
object QueuedInterruptRecordCodec {
    fun encode(records: List<QueuedInterruptRecord>): String = JSONArray().apply {
        records.distinctBy(QueuedInterruptRecord::runId).forEach { record ->
            put(JSONObject().apply {
                put("hostId", record.hostId)
                put("sessionId", record.sessionId)
                put("runId", record.runId)
                put("text", record.text.take(8_000))
                put("mode", record.mode.name.lowercase())
            })
        }
    }.toString()

    fun decode(raw: String): List<QueuedInterruptRecord> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hostId = item.optString("hostId")
                val sessionId = item.optString("sessionId")
                val runId = item.optString("runId")
                val text = item.optString("text")
                if (hostId.isBlank() || sessionId.isBlank() || runId.isBlank() || text.isBlank()) continue
                val mode = FollowUpMode.entries.firstOrNull {
                    it.name.equals(item.optString("mode"), ignoreCase = true)
                } ?: FollowUpMode.Interrupt
                add(QueuedInterruptRecord(hostId, sessionId, runId, text, mode))
            }
        }.distinctBy(QueuedInterruptRecord::runId)
    }.getOrDefault(emptyList())
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

    override fun loadRunCheckpoints(): List<RunCheckpoint> {
        preferences.getString(RUN_CHECKPOINTS_KEY, null)?.let { raw ->
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val hostId = item.optString("hostId")
                        val sessionId = item.optString("sessionId")
                        val runId = item.optString("runId")
                        if (hostId.isNotBlank() && sessionId.isNotBlank() && runId.isNotBlank()) {
                            add(
                                RunCheckpoint(
                                    hostId,
                                    sessionId,
                                    runId,
                                    item.optLong("lastEventId").takeIf {
                                        item.has("lastEventId") && it > 0L
                                    },
                                ),
                            )
                        }
                    }
                }.distinct()
            }.getOrDefault(emptyList())
        }

        // v1 stored one checkpoint in three separate preferences. Keep it
        // recoverable, then write v2 on the next checkpoint update.
        val hostId = preferences.getString("run_host_id", null) ?: return emptyList()
        val sessionId = preferences.getString("run_session_id", null) ?: return emptyList()
        val runId = preferences.getString("run_id", null) ?: return emptyList()
        return listOf(RunCheckpoint(hostId, sessionId, runId))
    }

    override fun saveRunCheckpoints(checkpoints: List<RunCheckpoint>) {
        // commit() is intentional: once submitRun returns, the checkpoint must
        // be durable before Android can tear down the Activity/process.
        preferences.edit()
            .putString(
                RUN_CHECKPOINTS_KEY,
                JSONArray().apply {
                    checkpoints.distinct().forEach { checkpoint ->
                        put(JSONObject().apply {
                            put("hostId", checkpoint.hostId)
                            put("sessionId", checkpoint.sessionId)
                            put("runId", checkpoint.runId)
                            checkpoint.lastEventId?.let { put("lastEventId", it) }
                        })
                    }
                }.toString(),
            )
            .remove("run_host_id")
            .remove("run_session_id")
            .remove("run_id")
            .commit()
    }

    override fun loadRunCheckpoint(): RunCheckpoint? = loadRunCheckpoints().firstOrNull()

    override fun saveRunCheckpoint(checkpoint: RunCheckpoint) {
        saveRunCheckpoints(listOf(checkpoint))
    }

    override fun clearRunCheckpoint() {
        preferences.edit()
            .remove(RUN_CHECKPOINTS_KEY)
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

    override fun loadUnknownOutcomeRecords(): List<UnknownOutcomeRecord> = preferences
        .getString(UNKNOWN_OUTCOMES_KEY, null)
        ?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val hostId = item.optString("hostId")
                        val sessionId = item.optString("sessionId")
                        val text = item.optString("text")
                        if (hostId.isNotBlank() && sessionId.isNotBlank() && text.isNotBlank()) {
                            add(
                                UnknownOutcomeRecord(
                                    hostId = hostId,
                                    sessionId = sessionId,
                                    baselineCount = item.optInt("baselineCount").coerceAtLeast(0),
                                    text = text,
                                    evidence = item.optBoolean("evidence"),
                                    timedOut = item.optBoolean("timedOut"),
                                ),
                            )
                        }
                    }
                }.distinctBy { it.hostId to it.sessionId }
            }.getOrDefault(emptyList())
        }
        .orEmpty()

    override fun saveUnknownOutcomeRecords(records: List<UnknownOutcomeRecord>) {
        preferences.edit().putString(
            UNKNOWN_OUTCOMES_KEY,
            JSONArray().apply {
                records.distinctBy { it.hostId to it.sessionId }.forEach { record ->
                    put(JSONObject().apply {
                        put("hostId", record.hostId)
                        put("sessionId", record.sessionId)
                        put("baselineCount", record.baselineCount.coerceAtLeast(0))
                        put("text", record.text.take(8_000))
                        put("evidence", record.evidence)
                        put("timedOut", record.timedOut)
                    })
                }
            }.toString(),
        ).commit()
    }

    override fun loadQueuedInterruptRecords(): List<QueuedInterruptRecord> = preferences
        .getString(QUEUED_INTERRUPTS_KEY, null)
        ?.let(QueuedInterruptRecordCodec::decode)
        .orEmpty()

    override fun saveQueuedInterruptRecords(records: List<QueuedInterruptRecord>) {
        preferences.edit().putString(
            QUEUED_INTERRUPTS_KEY,
            QueuedInterruptRecordCodec.encode(records),
        ).commit()
    }

    override fun loadNotificationHostIds(): Set<String> =
        preferences.getStringSet("notification_host_ids", emptySet()).orEmpty().toSet()

    override fun saveNotificationHostIds(hostIds: Set<String>) {
        preferences.edit().putStringSet("notification_host_ids", hostIds.toSet()).apply()
    }

    override fun loadMonitoredHostIds(): Set<String> = if (preferences.contains("monitored_host_ids")) {
        preferences.getStringSet("monitored_host_ids", emptySet()).orEmpty().toSet()
    } else {
        loadNotificationHostIds()
    }

    override fun saveMonitoredHostIds(hostIds: Set<String>) {
        preferences.edit().putStringSet("monitored_host_ids", hostIds.toSet()).apply()
    }

    fun loadMobileRegistrationStatuses(): List<MobileRegistrationStatus> =
        synchronized(mobileRegistrationStatusLock) { loadMobileRegistrationStatusesUnsafe() }

    fun markMobileRegistrationPending(
        hostIds: Set<String>,
        desiredHostIds: Set<String>,
    ): List<MobileRegistrationStatus> = updateMobileRegistrationStatuses { current ->
        markMobileRegistrationsPending(current, hostIds, desiredHostIds)
    }

    internal fun applyMobileRegistrationReport(
        hostIds: Set<String>,
        desiredHostIds: Set<String>,
        report: MobileRegistrationReport,
        nowMillis: Long,
        willRetry: Boolean,
    ): List<MobileRegistrationStatus> = updateMobileRegistrationStatuses { current ->
        applyMobileRegistrationReportToStatuses(
            current = current,
            hostIds = hostIds,
            desiredHostIds = desiredHostIds,
            report = report,
            nowMillis = nowMillis,
            willRetry = willRetry,
        )
    }

    override fun loadOverlayEnabled(): Boolean = preferences.getBoolean("overlay_enabled", false)

    override fun saveOverlayEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("overlay_enabled", enabled).apply()
    }

    override fun loadCrashReportingEnabled(): Boolean =
        preferences.getBoolean("crash_reporting_enabled", false)

    @SuppressLint("ApplySharedPref")
    override fun saveCrashReportingEnabled(enabled: Boolean) {
        // Consent is read during Application startup, so persist it before
        // enabling the in-process reporter and fail closed after a restart.
        preferences.edit().putBoolean("crash_reporting_enabled", enabled).commit()
    }

    override fun loadAttentionItems(): List<AttentionItem> = preferences
        .getString(ATTENTION_ITEMS_KEY, null)
        ?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val hostId = item.optString("hostId")
                        val sessionId = item.optString("sessionId")
                        if (hostId.isNotBlank() && sessionId.isNotBlank()) {
                            add(
                                AttentionItem(
                                    hostId = hostId,
                                    sessionId = sessionId,
                                    title = item.optString("title").ifBlank { "Hermes session" },
                                    state = item.optString("state").ifBlank { "updated" },
                                ),
                            )
                        }
                    }
                }.distinctBy { it.hostId to it.sessionId }
            }.getOrDefault(emptyList())
        }
        .orEmpty()

    override fun saveAttentionItems(items: List<AttentionItem>) {
        preferences.edit().putString(
            ATTENTION_ITEMS_KEY,
            JSONArray().apply {
                items.distinctBy { it.hostId to it.sessionId }.forEach { item ->
                    put(JSONObject().apply {
                        put("hostId", item.hostId)
                        put("sessionId", item.sessionId)
                        put("title", item.title.take(120))
                        put("state", item.state.take(80))
                    })
                }
            }.toString(),
        ).apply()
    }

    override fun loadInstallationId(): String? = preferences.getString("installation_id", null)

    override fun getOrCreateInstallationId(): String = loadInstallationId() ?: java.util.UUID.randomUUID().toString().also {
        preferences.edit().putString("installation_id", it).commit()
    }

    private fun updateMobileRegistrationStatuses(
        transform: (List<MobileRegistrationStatus>) -> List<MobileRegistrationStatus>,
    ): List<MobileRegistrationStatus> = synchronized(mobileRegistrationStatusLock) {
        transform(loadMobileRegistrationStatusesUnsafe()).also { statuses ->
            // Commit before WorkManager is enqueued so process death cannot
            // lose the desired/pending state that explains the queued work.
            preferences.edit()
                .putString(MOBILE_REGISTRATION_STATUSES_KEY, MobileRegistrationStatusCodec.encode(statuses))
                .commit()
        }
    }

    private fun loadMobileRegistrationStatusesUnsafe(): List<MobileRegistrationStatus> =
        preferences.getString(MOBILE_REGISTRATION_STATUSES_KEY, null)
            ?.let(MobileRegistrationStatusCodec::decode)
            .orEmpty()

    private companion object {
        const val RUN_CHECKPOINTS_KEY = "run_checkpoints_v2"
        const val ATTENTION_ITEMS_KEY = "attention_items_v1"
        const val UNKNOWN_OUTCOMES_KEY = "unknown_outcomes_v1"
        const val QUEUED_INTERRUPTS_KEY = "queued_interrupts_v1"
        const val MOBILE_REGISTRATION_STATUSES_KEY = "mobile_registration_statuses_v1"
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
