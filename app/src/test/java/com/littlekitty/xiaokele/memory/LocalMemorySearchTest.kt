package com.littlekitty.xiaokele.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.memory.MatchKind
import com.littlekitty.xiaokele.core.database.memory.MemoryQuery
import com.littlekitty.xiaokele.core.database.memory.VectorBlobCodec
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingResult
import com.littlekitty.xiaokele.core.network.embedding.FakeEmbeddingMode
import com.littlekitty.xiaokele.core.network.embedding.FakeEmbeddingService
import com.littlekitty.xiaokele.core.network.embedding.UnconfiguredEmbeddingServiceAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LocalMemorySearchTest {

    private lateinit var db: AppDatabase
    private val now = Instant.parse("2026-08-16T10:00:00Z")
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = this@LocalMemorySearchTest.now
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.memoryDao().upsert(
            MemoryEntity(
                id = "mem_00000000000000000000000000000001",
                type = MemoryTypeNames.USER_PREFERENCE,
                content = "测试用户夜班前不喜欢被反复催促",
                importance = 0.8,
                confidence = 0.9,
                status = MemoryStatuses.ACTIVE,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = "mem_00000000000000000000000000000002",
                type = MemoryTypeNames.USER_PREFERENCE,
                content = "测试用户喜欢下雨天吃火锅",
                importance = 0.8,
                confidence = 0.9,
                status = MemoryStatuses.ACTIVE,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun unconfiguredEmbeddingFallsBackToLexicalRelevantMemoriesOnly() = runBlocking {
        val search = LocalMemorySearch(db, UnconfiguredEmbeddingServiceAdapter(), timeProvider)

        val hits = search.search(MemoryQuery(text = "夜班前不要催我"))

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.matchedBy == MatchKind.LEXICAL })
        assertTrue(hits.first().memory.content.contains("夜班前"))
    }

    @Test
    fun fakeEmbeddingFailureFallsBackToMetadataLexical() = runBlocking {
        val search = LocalMemorySearch(db, FakeEmbeddingService(FakeEmbeddingMode.FAILURE), timeProvider)

        val hits = search.search(MemoryQuery(text = "夜班前不要催我"))

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.matchedBy == MatchKind.LEXICAL })
    }

    @Test
    fun vectorSearchRanksSemanticallyRelatedMemoryFirst() = runBlocking {
        val fakeEmbedding = FakeEmbeddingService(FakeEmbeddingMode.SUCCESS)
        val memories = db.memoryDao().activeAt(now.toEpochMilli())
        val vectors = (fakeEmbedding.embed(memories.map { it.content }) as EmbeddingResult.Success).batch
        memories.forEachIndexed { index, memory ->
            db.memoryEmbeddingDao().upsert(
                MemoryEmbeddingEntity(
                    memoryId = memory.id,
                    modelId = vectors.modelId,
                    dimension = vectors.dimension,
                    vectorBlob = VectorBlobCodec.encode(vectors.vectors[index].values),
                    createdAt = now.toEpochMilli(),
                ),
            )
        }
        val search = LocalMemorySearch(db, fakeEmbedding, timeProvider)

        val hits = search.search(MemoryQuery(text = "夜班前不要催我"))

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().matchedBy == MatchKind.VECTOR)
        assertTrue(hits.first().memory.content.contains("夜班前"))
    }

    @Test
    fun metadataFiltersExcludeExpiredAndOtherTypes() = runBlocking {
        db.memoryDao().upsert(
            MemoryEntity(
                id = "mem_00000000000000000000000000000003",
                type = MemoryTypeNames.RELATIONSHIP_EVENT,
                content = "夜班前的旧关系事件",
                importance = 0.9,
                confidence = 0.9,
                validUntil = now.toEpochMilli() - 1,
                status = MemoryStatuses.ACTIVE,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
        val search = LocalMemorySearch(db, UnconfiguredEmbeddingServiceAdapter(), timeProvider)

        val hits = search.search(
            MemoryQuery(
                text = "夜班前",
                types = setOf(MemoryTypeNames.USER_PREFERENCE),
            ),
        )

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.memory.type == MemoryTypeNames.USER_PREFERENCE })
        assertEquals(1, hits.size)
    }
}
