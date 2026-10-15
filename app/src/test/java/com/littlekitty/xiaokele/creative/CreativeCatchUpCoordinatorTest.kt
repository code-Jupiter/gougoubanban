package com.littlekitty.xiaokele.creative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.creative.CreativeCoverageRoles
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CreativeCatchUpCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var time: MutableTimeProvider

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        time = MutableTimeProvider(Instant.parse("2026-09-04T02:00:00Z"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun modelNoActionClosesDailyTaskWithoutCreatingWork() = runBlocking {
        val fake = RecordingCreativeModel { """{"outcome":"NO_ACTION","source_ids":[],"risk_level":"LOW"}""" }
        val coordinator = CreativeCatchUpCoordinator(db, fake, time)
        insertUserMessage("msg_no_action", "普通的一句闲聊", "2026-09-03T02:00:00Z")

        coordinator.catchUp()
        val report = coordinator.catchUp()

        assertEquals("NO_ACTION", report.status)
        assertTrue(fake.contexts.isNotEmpty())
        assertTrue(db.creativeWorkDao().recent(10).isEmpty())
        assertEquals("NO_ACTION", db.creativeTaskRunDao().recent(10).first().status)
    }

    @Test
    fun modelDiaryCreatesWorkAndPrimarySourceLinkButNotMemory() = runBlocking {
        val fake = RecordingCreativeModel {
            """
            {
              "outcome":"DAILY_DIARY",
              "work_type":"DAILY_DIARY",
              "title":"风经过窗边",
              "content":"我记得今天窗边的风，也记得自己想安静陪一会儿。",
              "summary":"测试小狗记下一个被风吹亮的晚上。",
              "dog_emotion_summary":"我有一点想陪伴的心情。",
              "source_ids":["msg_diary"],
              "risk_level":"LOW",
              "new_meaning":"这个细节让我更清楚自己想如何陪伴。"
            }
            """.trimIndent()
        }
        val coordinator = CreativeCatchUpCoordinator(db, fake, time)
        insertUserMessage("msg_diary", "今天的风很轻，我忽然想安静一会儿。", "2026-09-03T02:00:00Z")

        coordinator.catchUp()
        val report = coordinator.catchUp()

        assertEquals("COMPLETED", report.status)
        val work = db.creativeWorkDao().recent(10).single()
        assertEquals("DAILY_DIARY", work.workType)
        val link = db.creativeSourceLinkDao().forWork(work.id).single()
        assertEquals(CreativeCoverageRoles.PRIMARY, link.coverageRole)
        assertEquals(0, db.memoryDao().active(null).size)
    }

    private suspend fun insertUserMessage(id: String, content: String, createdAt: String) {
        db.messageDao().upsert(
            MessageEntity(
                id = id,
                role = "USER",
                content = content,
                createdAt = Instant.parse(createdAt).toEpochMilli(),
                status = "COMPLETED",
            ),
        )
    }

    private class RecordingCreativeModel(
        private val response: (com.littlekitty.xiaokele.core.common.protocol.ContextPack) -> String,
    ) : CreativeEvaluationModelService {
        val contexts = mutableListOf<com.littlekitty.xiaokele.core.common.protocol.ContextPack>()

        override suspend fun generateCreativeEvaluation(
            context: com.littlekitty.xiaokele.core.common.protocol.ContextPack,
        ): ModelServiceResult {
            contexts += context
            return ModelServiceResult.Success(response(context))
        }
    }
}
