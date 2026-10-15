package com.littlekitty.xiaokele.discovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DiscoveryKnowledgeTest {

    private lateinit var cache: DiscoveryCacheStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(DiscoveryCacheStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        cache = DiscoveryCacheStore(context)
    }

    @Test
    fun freshKnowledgeKeepsCategoriesAndSafeSourceUrls() {
        cache.save(
            items = listOf(
                item("food", "https://example.com/food"),
                item("events", "https://example.com/event"),
                item("other", "https://example.com/other"),
                item("food", "file:///tmp/not-safe"),
            ),
            generatedAt = "2026-08-22T08:00:00+08:00",
        )

        val snapshot = cache.loadFresh(System.currentTimeMillis())

        assertEquals(listOf("food", "events"), snapshot?.items?.map { it.category })
    }

    @Test
    fun staleKnowledgeIsNotProvidedToChat() {
        cache.save(listOf(item("food", "https://example.com/food")), null)

        assertNull(cache.loadFresh(System.currentTimeMillis() + DiscoveryCacheStore.MAX_CHAT_AGE_MILLIS + 1L))
    }

    private fun item(category: String, url: String) = DiscoveryItem(
        id = "$category-$url",
        area = "liandu",
        category = category,
        title = category,
        summary = "summary",
        sourceName = "source",
        sourceUrl = url,
    )
}
