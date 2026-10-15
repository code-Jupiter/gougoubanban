package com.littlekitty.xiaokele.discovery

import android.content.Context
import com.littlekitty.xiaokele.core.common.json.JsonCodec

data class DiscoveryKnowledgeSnapshot(
    val items: List<DiscoveryItem>,
    val generatedAt: String?,
    val savedAt: Long,
)

interface DiscoveryKnowledgeProvider {
    fun loadFresh(nowMillis: Long): DiscoveryKnowledgeSnapshot?
}

class DiscoveryCacheStore(context: Context) : DiscoveryKnowledgeProvider {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(items: List<DiscoveryItem>, generatedAt: String?) {
        prefs.edit()
            .putString(KEY_ITEMS, JsonCodec.encode(items))
            .putString(KEY_GENERATED_AT, generatedAt)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): List<DiscoveryItem> =
        prefs.getString(KEY_ITEMS, null)
            ?.let { runCatching { JsonCodec.decode<List<DiscoveryItem>>(it) }.getOrNull() }
            .orEmpty()

    fun lastSavedAt(): Long = prefs.getLong(KEY_SAVED_AT, 0L)

    fun lastGeneratedAt(): String? = prefs.getString(KEY_GENERATED_AT, null)

    override fun loadFresh(nowMillis: Long): DiscoveryKnowledgeSnapshot? {
        val savedAt = lastSavedAt()
        if (savedAt <= 0L || nowMillis - savedAt > MAX_CHAT_AGE_MILLIS) return null
        val items = load()
            .filter { it.category == "food" || it.category == "events" }
            .filter { com.littlekitty.xiaokele.provider.isSafeHttpUrl(it.sourceUrl) }
        if (items.isEmpty()) return null
        return DiscoveryKnowledgeSnapshot(
            items = items,
            generatedAt = lastGeneratedAt(),
            savedAt = savedAt,
        )
    }

    companion object {
        const val PREFS = "little_kitty_discovery_cache"
        const val KEY_ITEMS = "items_v1"
        const val KEY_GENERATED_AT = "generated_at"
        const val KEY_SAVED_AT = "saved_at"
        const val MAX_CHAT_AGE_MILLIS = 48L * 60L * 60L * 1000L
    }
}
