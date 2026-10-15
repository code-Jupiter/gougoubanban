package com.littlekitty.xiaokele.rhythm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeTopicCompletionStoreTest {

    @Test
    fun completedDaySurvivesNewStoreInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("little_kitty_wake_topic_completion", Context.MODE_PRIVATE).edit().clear().commit()
        val first = WakeTopicCompletionStore(context)

        assertFalse(first.isCompleted("20260906"))
        assertTrue(first.markCompleted("20260906", 1_000L))
        assertTrue(WakeTopicCompletionStore(context).isCompleted("20260906"))
    }
}
