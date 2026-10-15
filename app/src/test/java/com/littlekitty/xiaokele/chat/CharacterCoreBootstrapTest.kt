package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CharacterCoreBootstrapTest {

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
    fun upgradesLegacyCustomCoreToNeutralPublicFallback() = runBlocking {
        db.characterCoreDao().upsert(
            CharacterCoreEntity(
                id = CharacterCoreBootstrap.BOOTSTRAP_ID,
                version = 2,
                contentJson = """{"relationship":"定制角色关系"}""",
                createdAt = 1L,
            ),
        )

        CharacterCoreBootstrap.ensureSeeded(db, now = 2L)

        val current = db.characterCoreDao().latest()
        assertEquals(CharacterCoreBootstrap.BOOTSTRAP_VERSION, current?.version)
        val core = JsonCodec.json.parseToJsonElement(current?.contentJson.orEmpty()).jsonObject
        // A generic product must not prescribe how the companion relates to the
        // user; the relationship comes from first-create input.
        assertEquals(null, core["relationship"]?.jsonPrimitive?.content)
        assertEquals("未命名的小狗", core["name"]?.jsonPrimitive?.content)
        val expressionDna = core["expression_dna"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        assertTrue(expressionDna.any { it.contains("用户明确确认") })
        assertTrue(expressionDna.any { it.contains("不得单独推断") })
        assertTrue(expressionDna.none { it.contains("专属") })
    }

    @Test
    fun repairsStaleCoreEvenWhenVersionMatches() = runBlocking {
        db.characterCoreDao().upsert(
            CharacterCoreEntity(
                id = CharacterCoreBootstrap.BOOTSTRAP_ID,
                version = CharacterCoreBootstrap.BOOTSTRAP_VERSION,
                contentJson = """{"relationship":"定制角色关系"}""",
                createdAt = 1L,
            ),
        )

        CharacterCoreBootstrap.ensureSeeded(db, now = 2L)

        val relationship = JsonCodec.json.parseToJsonElement(
            db.characterCoreDao().latest()?.contentJson.orEmpty(),
        ).jsonObject["relationship"]?.jsonPrimitive?.content
        // The stale placeholder is repaired without inventing a relationship.
        assertEquals(null, relationship)
    }
}
