package com.littlekitty.xiaokele.core.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatch
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatchOperationNames
import com.littlekitty.xiaokele.core.common.protocol.BehaviorTendencyKeyNames
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatch
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatchOperationNames
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.state.BehaviorTendencyStatuses
import com.littlekitty.xiaokele.core.database.state.UserTraitStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BehaviorTrainerTest {

    private lateinit var db: AppDatabase
    private lateinit var trainer: BehaviorTrainer
    private lateinit var traitRepo: UserTraitRepository
    private val now = 1_700_000_000_000L
    private val memoryId = "mem_00000000000000000000000000000001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trainer = BehaviorTrainer(db, db.behaviorTendencyDao())
        traitRepo = UserTraitRepository(db, db.userTraitDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun correctedTendencyCreatesSuppressedLegacyAndNewActiveValue() = runBlocking {
        trainer.ensureDefault(BehaviorTendencyKeyNames.PRE_SHIFT_REMINDER_INTENSITY, now)

        val outcome = trainer.applyPatch(
            BehaviorPatch(
                key = BehaviorTendencyKeyNames.PRE_SHIFT_REMINDER_INTENSITY,
                operation = BehaviorPatchOperationNames.DECREASE,
                amount = 0.2,
                keepAsLegacy = true,
                evidenceMemoryIds = listOf(memoryId),
            ),
            derivedEvidenceMemoryIds = listOf(memoryId),
            now = now + 1,
        )

        assertTrue(outcome.applied)
        assertTrue(outcome.legacyCreated)
        assertEquals(0.3, outcome.newValue ?: 0.0, 0.0001)

        val rows = db.behaviorTendencyDao().forKey(BehaviorTendencyKeyNames.PRE_SHIFT_REMINDER_INTENSITY)
        val active = rows.single { it.status == BehaviorTendencyStatuses.ACTIVE }
        val legacy = rows.single { it.status == BehaviorTendencyStatuses.SUPPRESSED_LEGACY }
        assertEquals("0.3000", active.currentValue)
        assertEquals("0.5000", legacy.currentValue)
        assertEquals(0.85, legacy.suppressionLevel ?: 0.0, 0.0001)
        assertTrue(legacy.legacyExpressionRuleJson.orEmpty().contains("never_primary"))
        assertTrue(active.sourceMemoryIdsJson.orEmpty().contains(memoryId))
    }

    @Test
    fun legacyRowsHaveNoPathBackToActive() = runBlocking {
        trainer.applyPatch(
            BehaviorPatch(
                key = BehaviorTendencyKeyNames.REMINDER_INTENSITY,
                operation = BehaviorPatchOperationNames.SET,
                amount = 0.8,
                keepAsLegacy = false,
            ),
            derivedEvidenceMemoryIds = emptyList(),
            now = now,
        )
        trainer.applyPatch(
            BehaviorPatch(
                key = BehaviorTendencyKeyNames.REMINDER_INTENSITY,
                operation = BehaviorPatchOperationNames.DECREASE,
                amount = 0.3,
                keepAsLegacy = true,
            ),
            derivedEvidenceMemoryIds = emptyList(),
            now = now + 1,
        )

        val rows = db.behaviorTendencyDao().forKey(BehaviorTendencyKeyNames.REMINDER_INTENSITY)
        assertEquals(1, rows.count { it.status == BehaviorTendencyStatuses.ACTIVE })
        assertEquals(1, rows.count { it.status == BehaviorTendencyStatuses.SUPPRESSED_LEGACY })
        assertEquals(0, rows.count { it.status == BehaviorTendencyStatuses.ACTIVE && rows.any { legacy -> legacy.id == it.id && legacy.status == BehaviorTendencyStatuses.SUPPRESSED_LEGACY } })
        val legacy = rows.single { it.status == BehaviorTendencyStatuses.SUPPRESSED_LEGACY }
        assertFalse(legacy.status == BehaviorTendencyStatuses.ACTIVE)
    }

    @Test
    fun immutableCharacterCorePatchIsRejectedByTrainer() = runBlocking {
        val outcome = trainer.applyPatch(
            BehaviorPatch(
                key = "character_core",
                operation = BehaviorPatchOperationNames.SET,
                amount = 1.0,
            ),
            derivedEvidenceMemoryIds = emptyList(),
            now = now,
        )

        assertFalse(outcome.applied)
        assertEquals("immutable_character_core", outcome.rejectedReason)
        assertEquals(0, db.behaviorTendencyDao().count())
    }

    @Test
    fun userTraitPatchPersistsValueConfidenceAndEvidence() = runBlocking {
        val outcome = traitRepo.applyPatch(
            UserTraitPatch(
                key = "food_preference",
                operation = UserTraitPatchOperationNames.UPSERT,
                value = buildJsonObject { put("likes", "清淡") },
                confidence = 0.8,
            ),
            derivedEvidenceMemoryIds = listOf(memoryId),
            now = now,
        )

        assertTrue(outcome.applied)
        val trait = db.userTraitDao().byKey("food_preference")
        assertEquals(UserTraitStatuses.ACTIVE, trait?.status)
        assertEquals("""{"likes":"清淡"}""", trait?.valueJson)
        assertTrue(JsonCodec.decode<List<String>>(trait?.evidenceMemoryIdsJson.orEmpty()).contains(memoryId))
    }

    @Test
    fun userTraitDeactivateKeepsEvidenceButMarksInactive() = runBlocking {
        traitRepo.applyPatch(
            UserTraitPatch(
                key = "food_preference",
                value = buildJsonObject { put("likes", "清淡") },
                confidence = 0.8,
            ),
            listOf(memoryId),
            now,
        )

        val outcome = traitRepo.applyPatch(
            UserTraitPatch(
                key = "food_preference",
                operation = UserTraitPatchOperationNames.DEACTIVATE,
                confidence = 0.8,
            ),
            listOf(memoryId),
            now + 1,
        )

        assertTrue(outcome.applied)
        assertEquals(UserTraitStatuses.INACTIVE, db.userTraitDao().byKey("food_preference")?.status)
        assertTrue(JsonCodec.decode<List<String>>(db.userTraitDao().all().single().evidenceMemoryIdsJson.orEmpty()).contains(memoryId))
    }
}
