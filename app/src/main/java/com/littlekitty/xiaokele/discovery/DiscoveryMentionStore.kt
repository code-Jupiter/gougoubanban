package com.littlekitty.xiaokele.discovery

import android.content.Context
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Registry of discovery items the companion has already offered as a topic.
 * A mentioned item never becomes a proactive nudge again; only the user
 * bringing it up themselves (chat, todo handoff) re-engages the content, and
 * that path does not consult this registry.
 */
@Serializable
private data class DiscoveryMentionDocument(
    val mentions: Map<String, Long> = emptyMap(),
)

class DiscoveryMentionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun isMentioned(item: DiscoveryItem, now: Long = System.currentTimeMillis()): Boolean {
        val mentions = read(now)
        return mentions.containsKey(item.id) || mentions.containsKey(mentionKeyOfUrl(item.sourceUrl))
    }

    @Synchronized
    fun markMentioned(items: List<DiscoveryItem>, now: Long = System.currentTimeMillis()) {
        if (items.isEmpty()) return
        val mentions = read(now).toMutableMap()
        items.forEach { item ->
            mentions[item.id] = now
            if (item.sourceUrl.isNotBlank()) mentions[mentionKeyOfUrl(item.sourceUrl)] = now
        }
        write(mentions)
    }

    /** Redacted diagnostic marker used to assert Debug dry-runs do not register mentions. */
    @Synchronized
    fun diagnosticSnapshotHash(now: Long = System.currentTimeMillis()): String {
        val keys = read(now).keys.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(keys.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun mentionKeyOfUrl(url: String): String = "url:$url"

    private fun read(now: Long): Map<String, Long> {
        val raw = prefs.getString(KEY_DOCUMENT, null) ?: return emptyMap()
        val document = runCatching { JsonCodec.decode<DiscoveryMentionDocument>(raw) }
            .getOrNull() ?: return emptyMap()
        return document.mentions.filterValues { mentionedAt ->
            now - mentionedAt <= RETENTION_MILLIS
        }
    }

    private fun write(mentions: Map<String, Long>) {
        prefs.edit()
            .putString(KEY_DOCUMENT, JsonCodec.encode(DiscoveryMentionDocument(mentions)))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "little_kitty_discovery_mentions"
        private const val KEY_DOCUMENT = "document"

        /**
         * Registrations outlive the 48h discovery cache by design: "mentioned
         * once, never proactively re-offered" should hold well beyond a cache
         * refresh cycle; 30 days bounds memory growth while keeping the dedup
         * meaningful for recurring listings.
         */
        const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
