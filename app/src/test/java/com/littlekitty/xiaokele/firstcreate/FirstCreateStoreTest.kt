package com.littlekitty.xiaokele.firstcreate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * First-create persistence and the runtime gate.
 *
 * The important guarantees here are that an abandoned flow is resumable and
 * that confirmation is one atomic step, because a half-committed first-create
 * would let the product run with an unconfirmed persona.
 */
@RunWith(RobolectricTestRunner::class)
class FirstCreateStoreTest {

    private lateinit var db: AppDatabase
    private var now = 1_800_000_000_000L
    private lateinit var store: FirstCreateStore

    @Before
    fun setUp() {
        // Another test class in this JVM may have left a global Koin app
        // running; this test does not use Koin, so make that state harmless.
        org.koin.core.context.stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = FirstCreateStore(db = db, timeProvider = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun readyDraft() = FirstCreateDraft(
        stage = FirstCreateStage.CONFIRM,
        petName = "豆豆",
        userAddress = "小雨",
        relationship = "一起住的小狗",
        genderExpression = GenderExpression.UNDISCLOSED,
        declaredAge = 30,
        declaredAgeRecordedAt = 999L,
        provider = SelectedGenerationProvider.MINIMAX_OFFICIAL,
        serviceValidation = ServiceValidationState(
            provider = SelectedGenerationProvider.MINIMAX_OFFICIAL.name,
            validated = true,
            validatedAt = 5L,
            modelId = "MiniMax-M2",
            endpointHost = "api.minimaxi.com",
            credentialFingerprint = "abcd",
        ),
        personaGeneratorProvider = SelectedGenerationProvider.MINIMAX_OFFICIAL,
        personaGeneratorModel = "MiniMax-M3",
        personaDraft = "名字：豆豆\n边界：尊重拒绝。",
    )

    @Test
    fun draftStartsEmptyAndResumesWhereItStopped() = runBlocking {
        val initial = store.loadDraft()
        // A brand-new install starts at the connection gate.
        assertEquals(FirstCreateStage.CONNECTION_GATE, initial.stage)

        store.saveDraft(initial.copy(petName = "豆豆", stage = FirstCreateStage.USER_PROFILE))

        // A new store instance simulates a process restart.
        val reloaded = FirstCreateStore(db, timeProvider = { now }).loadDraft()
        assertEquals(FirstCreateStage.USER_PROFILE, reloaded.stage)
        assertEquals("豆豆", reloaded.petName)
    }

    @Test
    fun corruptDraftDoesNotCrashAndRestartsTheFlow() = runBlocking {
        db.metadataDao().put(
            com.littlekitty.xiaokele.core.database.AppMetadataEntity(
                key = FirstCreateStore.DRAFT_KEY,
                value = "{ not valid json",
                updatedAtEpochMillis = now,
            ),
        )
        assertEquals(FirstCreateStage.CONNECTION_GATE, store.loadDraft().stage)
    }

    @Test
    fun gateIsClosedUntilConfirmation() = runBlocking {
        val gate = FirstCreateGate(store)
        assertFalse(gate.isCompleted())
        assertNull(store.completionEpochMillis())
        assertNotNull(gate.blockReason())

        store.saveDraft(readyDraft())
        // Saving a complete draft is not confirmation.
        assertFalse(gate.isCompleted())

        val result = store.confirm(readyDraft())
        assertTrue(result.isSuccess)
        assertTrue(gate.refresh())
        assertEquals(now, store.completionEpochMillis())
        assertNull(gate.blockReason())
    }

    @Test
    fun confirmationIsRefusedWhenTheDraftIsNotReady() = runBlocking {
        val incomplete = readyDraft().copy(personaGeneratorModel = "")

        val result = store.confirm(incomplete)

        assertTrue(result.isFailure)
        assertNull(store.completionEpochMillis())
        assertNull(db.characterCoreDao().latest())
    }

    @Test
    fun confirmationWritesProfilePersonaAndMarkerTogether() = runBlocking {
        store.confirm(readyDraft())

        val petName = db.userTraitDao().byKey(FirstCreateStore.KEY_PET_NAME)
        val address = db.userTraitDao().byKey(FirstCreateStore.KEY_USER_ADDRESS)
        val provider = db.userTraitDao().byKey(FirstCreateStore.KEY_SELECTED_PROVIDER)
        val persona = db.userTraitDao().byKey(FirstCreateStore.KEY_PERSONA_TEXT)
        val character = db.characterCoreDao().latest()

        assertNotNull(petName)
        assertNotNull(address)
        assertNotNull(provider)
        assertNotNull(persona)
        assertNotNull(character)

        // Declared age is recorded together with when it was declared.
        assertNotNull(db.userTraitDao().byKey(FirstCreateStore.KEY_DECLARED_AGE))
        assertNotNull(db.userTraitDao().byKey(FirstCreateStore.KEY_DECLARED_AGE_RECORDED_AT))

        // Only the selected provider is recorded.
        assertTrue(provider!!.valueJson.contains(SelectedGenerationProvider.MINIMAX_OFFICIAL.name))
        assertTrue(character!!.contentJson.contains("豆豆"))
        assertEquals(FirstCreateStore.CHARACTER_CORE_VERSION, character.version)
        // First-create facts are authoritative user declarations, not guesses.
        assertEquals("CORE", petName!!.profileLayer)
        assertEquals(1.0, petName.confidence, 0.0001)
    }

    @Test
    fun confirmedMarkerSurvivesRestart() = runBlocking {
        store.confirm(readyDraft())

        val reopened = FirstCreateStore(db, timeProvider = { now })
        assertTrue(reopened.isCompleted())
        assertEquals(now, reopened.completionEpochMillis())
    }

    @Test
    fun gateTreatsAnUnknownStateAsNotCompleted() = runBlocking {
        // A gate that has never been refreshed must not report completion.
        val gate = FirstCreateGate(store)
        assertFalse(gate.isCompletedCached())
        assertNotNull(gate.blockReason())
    }
}
