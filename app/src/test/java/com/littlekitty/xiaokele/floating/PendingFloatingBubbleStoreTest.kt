package com.littlekitty.xiaokele.floating

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PendingFloatingBubbleStoreTest {

    private lateinit var context: Context
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PendingFloatingBubbleStore(context).clear()
    }

    @After
    fun tearDown() {
        PendingFloatingBubbleStore(context).clear()
    }

    @Test
    fun independentlyCreatedStoresShareOnePriorityQueueAndSingleConsume() {
        val producer = PendingFloatingBubbleStore(context)
        val consumer = PendingFloatingBubbleStore(context)
        producer.enqueue(PendingFloatingBubble("高优先级", "evt_high", 80, now + 10_000), now)
        consumer.enqueue(PendingFloatingBubble("低优先级", "evt_low", 20, now + 10_000), now)

        val consumed = PendingFloatingBubbleStore(context).consumeIfValid(now)

        assertEquals("高优先级", consumed?.text)
        assertNull(producer.consumeIfValid(now))
    }
}
