package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageDaoRecentTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recentAscReturnsLatestWindowInChronologicalOrder() = runBlocking {
        repeat(25) { index ->
            db.messageDao().insert(
                MessageEntity(
                    id = "message-${index.toString().padStart(2, '0')}",
                    role = if (index % 2 == 0) "USER" else "ASSISTANT",
                    content = "message $index",
                    createdAt = index.toLong(),
                ),
            )
        }

        val result = db.messageDao().recentAsc(limit = 20)

        assertEquals((5L..24L).toList(), result.map { it.createdAt })
    }

    @Test
    fun deleteByIdRemovesOnlyTheSelectedChatRecord() = runBlocking {
        db.messageDao().insert(
            MessageEntity(id = "message-user", role = "USER", content = "用户记录", createdAt = 1L),
        )
        db.messageDao().insert(
            MessageEntity(id = "message-assistant", role = "ASSISTANT", content = "AI 记录", createdAt = 2L),
        )

        assertEquals(1, db.messageDao().deleteById("message-user"))
        assertEquals(null, db.messageDao().byId("message-user"))
        assertEquals("AI 记录", db.messageDao().byId("message-assistant")?.content)
        assertEquals(0, db.messageDao().deleteById("missing-message"))
    }
}
