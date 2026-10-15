package com.littlekitty.xiaokele.provider

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CompanionFallbackReplyCatalogTest {
    @Test fun generatesOnceAndReusesRepliesForTheConfirmedPersona() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            var calls = 0
            val gateway = object : TaskModelGateway {
                override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                    calls++
                    assertEquals(TaskKind.B2_EXPRESSION, request.task)
                    assertTrue(request.userPayload.contains("热情活泼"))
                    return TaskModelResult.Success(
                        content = """
                            GENERAL=我刚刚没接住这句话，先不乱说。
                            MISSING_INFORMATION=我还缺一点可靠依据，先不凭空猜。
                            CAPABILITY=这件事我现在没法真的替你完成。
                            PRIVACY=这部分需要先保护好你的隐私和权限。
                            UNSUPPORTED_FACT=我不能把没有确认的事说成真的。
                        """.trimIndent(),
                        snapshot = TaskModelRouter().resolve(TaskKind.B2_EXPRESSION),
                        trace = ServiceCallTrace("fake"),
                        provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                    )
                }
            }
            val time = object : TimeProvider {
                override fun now(): Instant = Instant.parse("2026-10-14T00:00:00Z")
                override fun zone(): ZoneId = ZoneId.of("UTC")
            }
            val catalog = CompanionFallbackReplyCatalog(db, gateway, time)
            val persona = ContextPack(
                currentContext = CurrentContext(""),
                character = CharacterContext(buildJsonObject {
                    put("display_name", "小满")
                    put("persona_anchor", "热情活泼")
                }),
            )

            val generated = catalog.ensureGenerated(persona)

            assertNotNull(generated)
            assertEquals("我刚刚没接住这句话，先不乱说。", generated?.general)
            assertEquals(1, calls)
            assertEquals(generated, catalog.cached(persona))
            assertEquals(generated, catalog.ensureGenerated(persona))
            assertEquals(1, calls)
        } finally {
            db.close()
        }
    }
}
