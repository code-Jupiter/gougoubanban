package com.littlekitty.xiaokele.core.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.MemoryCandidate
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryLearningRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MemoryRepository
    private val now = 1_700_000_000_000L
    private val messageId = "msg_00000000000000000000000000000001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MemoryRepository(db, db.memoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exactDuplicateIsMergedInPlace() = runBlocking {
        val candidate = MemoryCandidate(
            type = MemoryTypeNames.USER_PREFERENCE,
            content = "测试用户不喜欢被催促",
            importance = 0.6,
            confidence = 0.7,
            validity = MemoryValidityNames.LONG_TERM,
            evidenceMessageIds = listOf(messageId),
        )
        repo.writeCandidates(listOf(candidate), now)
        val firstId = db.memoryDao().activeAt(now).single().id

        val report = repo.writeCandidates(
            listOf(candidate.copy(importance = 0.8, confidence = 0.9)),
            now + 1,
        )

        assertEquals(0, report.inserted)
        assertEquals(1, report.merged)
        val active = db.memoryDao().activeAt(now + 1)
        assertEquals(1, active.size)
        assertEquals(firstId, active.single().id)
        assertEquals(0.8, active.single().importance, 0.0001)
        assertEquals(0.9, active.single().confidence, 0.0001)
    }

    @Test
    fun duplicateCandidatesInOneBatchMergeAgainstTheFirstWrite() = runBlocking {
        val candidate = MemoryCandidate(
            type = MemoryTypeNames.USER_PREFERENCE,
            content = "不喜欢临时改变计划",
            importance = 0.6,
            confidence = 0.7,
            validity = MemoryValidityNames.LONG_TERM,
            evidenceMessageIds = listOf(messageId),
        )

        val report = repo.writeCandidates(
            listOf(candidate, candidate.copy(importance = 0.9, confidence = 0.95)),
            now,
        )

        assertEquals(1, report.inserted)
        assertEquals(1, report.merged)
        val active = db.memoryDao().activeAt(now)
        assertEquals(1, active.size)
        assertEquals(0.9, active.single().importance, 0.0001)
        assertEquals(0.95, active.single().confidence, 0.0001)
    }

    @Test
    fun explicitSupersedesMovesOldMemoryToSuperseded() = runBlocking {
        val oldId = "mem_00000000000000000000000000000001"
        db.memoryDao().upsert(
            MemoryEntity(
                id = oldId,
                type = MemoryTypeNames.USER_PREFERENCE,
                content = "测试用户喜欢被催促",
                importance = 0.7,
                confidence = 0.8,
                status = MemoryStatuses.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val report = repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "测试用户不喜欢被催促",
                    importance = 0.9,
                    confidence = 0.95,
                    validity = MemoryValidityNames.LONG_TERM,
                    evidenceMessageIds = listOf(messageId),
                    supersedesMemoryId = oldId,
                ),
            ),
            now + 1,
        )

        assertEquals(1, report.inserted)
        assertEquals(1, report.superseded)
        assertEquals(MemoryStatuses.SUPERSEDED, db.memoryDao().byId(oldId)?.status)
        val replacement = db.memoryDao().byId(report.memoryIds.single())
        assertEquals(oldId, replacement?.supersedesMemoryId)
        assertEquals(MemoryStatuses.ACTIVE, replacement?.status)
    }

    @Test
    fun highSimilarityConflictWithStrongerConfidenceSupersedes() = runBlocking {
        repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "测试用户夜班前不喜欢被反复催促",
                    importance = 0.5,
                    confidence = 0.6,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
            now,
        )
        val oldId = db.memoryDao().activeAt(now).single().id

        val report = repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "测试用户夜班前不喜欢被反复催促，以后要安静一点",
                    importance = 0.8,
                    confidence = 0.9,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
            now + 1,
        )

        assertEquals(1, report.superseded)
        assertEquals(MemoryStatuses.SUPERSEDED, db.memoryDao().byId(oldId)?.status)
        assertEquals(1, db.memoryDao().activeAt(now + 1).size)
    }

    @Test
    fun expiredMemoryIsExcludedFromWriteMergeBase() = runBlocking {
        db.memoryDao().upsert(
            MemoryEntity(
                id = "mem_00000000000000000000000000000001",
                type = MemoryTypeNames.USER_PREFERENCE,
                content = "expired content",
                status = MemoryStatuses.ACTIVE,
                validUntil = now - 1,
                createdAt = now - 10,
                updatedAt = now - 10,
            ),
        )
        db.memoryDao().expireDue(now)

        val report = repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "expired content",
                    importance = 0.7,
                    confidence = 0.8,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
            now,
        )

        assertEquals(1, report.inserted)
        assertEquals(0, report.merged)
        assertEquals(MemoryStatuses.EXPIRED, db.memoryDao().byId("mem_00000000000000000000000000000001")?.status)
    }

    @Test
    fun validUntilParsedFromCandidateIsoInstant() = runBlocking {
        repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.SHORT_TERM_MEMORY,
                    content = "short",
                    importance = 0.5,
                    confidence = 0.5,
                    validity = MemoryValidityNames.SHORT_TERM,
                    validUntil = "2026-08-17T10:00:00Z",
                ),
            ),
            now,
        )

        val memory = db.memoryDao().activeAt(now).single()
        assertEquals(Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(), memory.validUntil)
        assertTrue((memory.validUntil ?: 0) > now)
    }

    @Test
    fun shortTermWithoutExplicitDateGetsDefaultTtlAndKeepsEmotionalAppraisal() = runBlocking {
        repo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.SHORT_TERM_MEMORY,
                    content = "近期夜班后睡眠较差",
                    importance = 0.7,
                    confidence = 0.8,
                    validity = MemoryValidityNames.SHORT_TERM,
                    emotionAtEvent = buildJsonObject { put("label", "疲惫") },
                    currentAppraisal = buildJsonObject { put("emotional_residue", 0.4) },
                ),
            ),
            now,
        )

        val memory = db.memoryDao().activeAt(now).single()
        assertEquals(now + MemoryRepository.SHORT_TERM_TTL_MILLIS, memory.validUntil)
        assertTrue(memory.emotionAtEventJson.orEmpty().contains("疲惫"))
        assertTrue(memory.currentAppraisalJson.orEmpty().contains("emotional_residue"))
        assertEquals(now, memory.lastReassessedAt)
    }
}
