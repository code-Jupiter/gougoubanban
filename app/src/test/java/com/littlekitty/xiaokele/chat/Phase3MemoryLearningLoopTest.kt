package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatch
import com.littlekitty.xiaokele.core.common.protocol.MemoryCandidate
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames
import com.littlekitty.xiaokele.core.common.protocol.PatternObservation
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.database.state.BehaviorTendencyStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.core.network.embedding.FakeEmbeddingService
import com.littlekitty.xiaokele.core.network.memory.FakeMemoryEvaluator
import com.littlekitty.xiaokele.core.network.memory.FakeMemoryEvaluatorMode
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationRequest
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationResult
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluator
import com.littlekitty.xiaokele.core.network.model.FakeModelMode
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.memory.LocalMemorySearch
import com.littlekitty.xiaokele.memory.MemoryAnalysisHook
import com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import com.littlekitty.xiaokele.memory.MemoryAnalysisRequest
import com.littlekitty.xiaokele.memory.MemoryReviewGateway
import com.littlekitty.xiaokele.memory.MemoryReviewIssue
import com.littlekitty.xiaokele.memory.MemoryReviewRequest
import com.littlekitty.xiaokele.memory.MemoryReviewResult
import com.littlekitty.xiaokele.provider.ReviewKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Phase3MemoryLearningLoopTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: TimeProvider
    private lateinit var fakeModel: FakeModelService
    private lateinit var fakeEmbedding: FakeEmbeddingService
    private lateinit var memoryRepo: MemoryRepository
    private lateinit var traitRepo: UserTraitRepository
    private lateinit var behaviorRepo: BehaviorTendencyRepository
    private lateinit var behaviorTrainer: BehaviorTrainer

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = object : TimeProvider {
            override fun now(): Instant = Instant.parse("2026-08-16T10:00:00Z")
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        fakeModel = FakeModelService(FakeModelMode.SUCCESS)
        fakeEmbedding = FakeEmbeddingService()
        memoryRepo = MemoryRepository(db, db.memoryDao())
        traitRepo = UserTraitRepository(db, db.userTraitDao())
        behaviorRepo = BehaviorTendencyRepository(db, db.behaviorTendencyDao())
        behaviorTrainer = BehaviorTrainer(db, db.behaviorTendencyDao())
        CharacterCoreBootstrap.ensureSeeded(db, timeProvider.now().toEpochMilli())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun correctionUpdatesTrainingStateAndChangesFollowingContext() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val pipeline = pipelineWith(processor)
        val outcome = pipeline.send("不要一直催我啦")

        assertTrue(outcome is ChatSendOutcome.Success)
        val success = outcome as ChatSendOutcome.Success
        assertEquals(MessageStatuses.COMPLETED, db.messageDao().byId(success.userMessageId)?.status)
        assertEquals(1, db.memoryDao().activeAt(timeProvider.now().toEpochMilli()).size)
        assertEquals(1, db.memoryEmbeddingDao().count())

        val active = behaviorRepo.activeTendencies().single { it.key == "pre_shift_reminder_intensity" }
        assertEquals("0.3000", active.currentValue)
        val legacy = behaviorRepo.legacyTendencies().single { it.key == "pre_shift_reminder_intensity" }
        assertEquals(BehaviorTendencyStatuses.SUPPRESSED_LEGACY, legacy.status)
        assertTrue(legacy.legacyExpressionRuleJson.orEmpty().contains("never_primary"))

        val userMessage = db.messageDao().byId(success.userMessageId)!!
        val search = LocalMemorySearch(db, fakeEmbedding, timeProvider)
        val nextContext = ChatContextBuilder(
            db = db,
            timeProvider = timeProvider,
            memorySearch = search,
            userTraitRepository = traitRepo,
            behaviorTendencyRepository = behaviorRepo,
        ).buildFor(userMessage.copy(content = "夜班前不要催我"))

        assertTrue(nextContext.retrievedMemories.isNotEmpty())
        assertTrue(nextContext.character.activeTendencies.any {
            it.toString().contains("pre_shift_reminder_intensity")
        })
        assertTrue(nextContext.character.relevantLegacyTendencies.any {
            it.toString().contains("never_primary")
        })
    }

    @Test
    fun contextBuilderIncludesUserProfile() = runBlocking {
        traitRepo.applyPatch(
            UserTraitPatch(
                key = "food_preference",
                value = buildJsonObject { put("likes", "清淡") },
                confidence = 0.8,
            ),
            derivedEvidenceMemoryIds = emptyList(),
            now = timeProvider.now().toEpochMilli(),
        )
        val context = ChatContextBuilder(
            db = db,
            timeProvider = timeProvider,
            memorySearch = LocalMemorySearch(db, fakeEmbedding, timeProvider),
            userTraitRepository = traitRepo,
            behaviorTendencyRepository = behaviorRepo,
        ).buildFor(
            com.littlekitty.xiaokele.core.database.entity.MessageEntity(
                id = "msg_00000000000000000000000000000001",
                role = "USER",
                content = "今天想吃清淡的",
                createdAt = timeProvider.now().toEpochMilli(),
                status = MessageStatuses.COMPLETED,
            ),
        )

        assertTrue(context.userProfile.any { it.toString().contains("food_preference") })
    }

    @Test
    fun analysisFailureDoesNotBlockOrRollbackCompletedChat() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.FAILURE),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val pipeline = pipelineWith(processor)
        val outcome = pipeline.send("这条消息的记忆分析会失败")

        assertTrue(outcome is ChatSendOutcome.Success)
        val success = outcome as ChatSendOutcome.Success
        assertEquals(MessageStatuses.COMPLETED, db.messageDao().byId(success.userMessageId)?.status)
        assertEquals(MessageStatuses.COMPLETED, db.messageDao().byId(success.assistantMessageId)?.status)
        assertEquals(0, db.memoryDao().count())
        assertEquals(0, db.behaviorTendencyDao().count())
    }

    @Test
    fun explicitForgetCanArchiveAnExistingMemory() = runBlocking {
        val report = memoryRepo.writeCandidates(
            listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "测试用户喜欢被反复提醒",
                    importance = 0.8,
                    confidence = 0.9,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
            timeProvider.now().toEpochMilli(),
        )
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )

        processor.applyRawEvaluation(
            JsonCodec.encode(MemoryEvaluation(deactivateMemoryIds = report.memoryIds)),
        )

        assertEquals(MemoryStatuses.ARCHIVED, db.memoryDao().byId(report.memoryIds.single())?.status)
    }

    /**
     * Live MiniMax-M3 answers memory evaluation in an operation dialect
     * (`{"memoryEvaluation":{"operations":[{"operation":"new",...}]}}`) even when
     * the canonical schema leads the prompt, so the app translates it locally
     * instead of discarding the user's explicit "please remember …".
     */
    @Test
    fun operationShapedEvaluationIsTranslatedIntoAStoredMemory() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val userId = "msg_00000000000000000000000000000abc"
        val assistantId = "msg_00000000000000000000000000000def"
        seedTurn(userId, assistantId, "please remember that my favourite drink is jasmine tea")

        val raw = """
            {"memoryEvaluation":{"eventId":"session:example","evaluatedAt":"2026-01-01T00:00:00.000Z",
             "operations":[{"operation":"new","evidenceId":"$userId","subject":"user",
               "time":"2026-01-01T00:00:00.000Z","validity":"long-term (stable preference)",
               "contentType":"user-statement","content":"User's favourite drink is jasmine tea.",
               "scope":"preferences","status":"proposed","notes":"Direct user self-report."}],
             "revise":[],"invalidate":[],"relate":[],"rejected":[],"summary":"one preference"}}
        """.trimIndent()

        val outcome = processor.applyRawEvaluation(raw)

        assertTrue("translation must apply a memory, got $outcome", outcome is MemoryAnalysisOutcome.Applied)
        assertEquals(1, db.memoryDao().count())
        val stored = db.memoryDao().activeAt(timeProvider.now().toEpochMilli()).single()
        assertEquals("User's favourite drink is jasmine tea.", stored.content)
        assertEquals(MemoryTypeNames.USER_PREFERENCE, stored.type)
        assertEquals(0.7, stored.importance, 0.0001)
    }

    @Test
    fun malformedOptionalMemoryItemDoesNotDiscardValidSibling() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val raw = buildJsonObject {
            put("memory_candidates", buildJsonArray {
                add(buildJsonObject {
                    put("type", MemoryTypeNames.USER_PREFERENCE)
                    put("content", "测试用户喜欢自然承接话题")
                    put("importance", 0.8)
                    put("confidence", 0.9)
                    put("validity", MemoryValidityNames.LONG_TERM)
                })
                add(buildJsonObject {
                    put("type", MemoryTypeNames.USER_PREFERENCE)
                    put("content", "这条数值越界，应该被单独丢弃")
                    put("importance", 3.0)
                    put("confidence", 0.9)
                    put("validity", MemoryValidityNames.LONG_TERM)
                })
            })
            put("user_trait_patches", buildJsonArray {})
            put("behavior_patches", buildJsonArray {})
            put("deactivate_memory_ids", buildJsonArray {})
            put("pattern_observations", buildJsonArray {})
        }.toString()

        val outcome = processor.applyRawEvaluation(raw)

        assertTrue(outcome is MemoryAnalysisOutcome.Applied)
        assertEquals(1, db.memoryDao().count())
        assertEquals("测试用户喜欢自然承接话题", db.memoryDao().activeAt(timeProvider.now().toEpochMilli()).single().content)
    }

    @Test
    fun invalidMemoryOutputGetsOneFormatRepairRetryWithExplicitHint() = runBlocking {
        val evaluator = OneInvalidThenValidMemoryEvaluator()
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = evaluator,
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val outcome = pipelineWith(processor).send("请记住我不喜欢被催")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(2, evaluator.calls)
        assertTrue(evaluator.repairInstruction.orEmpty().contains("mem_pending"))
        assertTrue(evaluator.repairInstruction.orEmpty().contains("emotion_at_event"))
        assertEquals(1, db.memoryDao().count())
    }

    @Test
    fun scalarRelationshipFieldIsNormalizedBeforeLocalValidation() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        val raw = """
            {"memory_candidates":[{"type":"RELATIONSHIP_EVENT","content":"这次聊天让我有点安心","importance":0.7,"confidence":0.8,"validity":"SHORT_TERM","emotion_at_event":"安心"}],"user_trait_patches":[],"behavior_patches":[],"deactivate_memory_ids":[],"pattern_observations":[]}
        """.trimIndent()

        val outcome = processor.applyRawEvaluation(raw)

        assertTrue(outcome is MemoryAnalysisOutcome.Applied)
        assertEquals(1, db.memoryDao().count())
    }

    @Test
    fun repeatedPatternAccumulatesEvidenceBeforeEnteringCoreProfile() = runBlocking {
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        repeat(3) { index ->
            processor.applyRawEvaluation(
                JsonCodec.encode(
                    MemoryEvaluation(
                        patternObservations = listOf(
                            PatternObservation(
                                key = "morning_greeting",
                                observation = "测试用户经常在早晨主动问候",
                                confidence = 0.8,
                                evidenceMessageIds = listOf(
                                    "msg_${(index + 10).toString(16).padStart(32, '0')}",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val pattern = traitRepo.active().single { it.key == "pattern.morning_greeting" }
        assertEquals(UserProfileLayerNames.CORE, pattern.profileLayer)
        assertTrue(pattern.valueJson.contains("\"evidence_count\":3"))
    }

    @Test
    fun dCandidateIsCommittedOnlyAfterIndependentReviewPasses() = runBlocking {
        val processor = processorWithReview(MemoryReviewResult.Passed())

        val outcome = pipelineWith(processor).send("请记住我不喜欢被催")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(1, db.memoryDao().count())
    }

    @Test
    fun relationshipCandidateUsesRelationshipConfirmationReviewRecipe() = runBlocking {
        val sourceId = "msg_00000000000000000000000000000051"
        db.messageDao().insert(
            MessageEntity(
                id = sourceId,
                role = "USER",
                content = "我明确确认愿意继续这项共同约定",
                createdAt = timeProvider.now().toEpochMilli(),
                status = MessageStatuses.COMPLETED,
            ),
        )
        var observedRequest: MemoryReviewRequest? = null
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
            memoryReviewGateway = MemoryReviewGateway { request ->
                observedRequest = request
                MemoryReviewResult.Passed()
            },
        )

        val outcome = processor.reviewAndApplyRawEvaluation(
            rawMemoryEvaluationJson = JsonCodec.encode(
                MemoryEvaluation(
                    memoryCandidates = listOf(
                        MemoryCandidate(
                            type = MemoryTypeNames.RELATIONSHIP_EVENT,
                            content = "用户明确说愿意继续共同确认这件事",
                            importance = 0.7,
                            confidence = 0.8,
                            validity = MemoryValidityNames.SHORT_TERM,
                            evidenceMessageIds = listOf(sourceId),
                            sourceMessageId = sourceId,
                        ),
                    ),
                ),
            ),
            sourcePayloadJson = "{\"submitted_messages\":[]}",
            allowedMessageIds = setOf(sourceId),
        )

        assertTrue(outcome is MemoryAnalysisOutcome.Applied)
        assertEquals(ReviewKind.RELATIONSHIP_CONFIRMATION, observedRequest?.reviewKind)
    }

    @Test
    fun reviseAndRejectReviewFailClosedWithoutWritingMemory() = runBlocking {
        val revise = processorWithReview(
            MemoryReviewResult.Blocked(
                verdict = "REVISE",
                issues = listOf(MemoryReviewIssue(type = "FACT", minimumChange = "缺少原始来源")),
            ),
        )
        val rejected = processorWithReview(MemoryReviewResult.Blocked("REJECT", emptyList()))

        assertTrue(pipelineWith(revise).send("请记住这件事") is ChatSendOutcome.Success)
        assertEquals(0, db.memoryDao().count())
        assertTrue(pipelineWith(rejected).send("请再记住另一件事") is ChatSendOutcome.Success)
        assertEquals(0, db.memoryDao().count())
    }

    @Test
    fun deletingSourceMessageArchivesDerivedMemoryWithoutModelCall() = runBlocking {
        val sourceId = "msg_00000000000000000000000000000042"
        val source = MessageEntity(
            id = sourceId,
            role = "USER",
            content = "来源消息",
            createdAt = timeProvider.now().toEpochMilli(),
            status = MessageStatuses.COMPLETED,
        )
        db.messageDao().insert(source)
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.FAILURE),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
        )
        processor.applyRawEvaluation(
            JsonCodec.encode(
                MemoryEvaluation(
                    memoryCandidates = listOf(
                        MemoryCandidate(
                            type = MemoryTypeNames.USER_PREFERENCE,
                            content = "来源偏好",
                            importance = 0.8,
                            confidence = 0.9,
                            validity = MemoryValidityNames.LONG_TERM,
                            evidenceMessageIds = listOf(sourceId),
                            sourceMessageId = sourceId,
                        ),
                    ),
                ),
            ),
        )
        val memoryId = db.memoryDao().all().single().id

        db.messageDao().deleteById(sourceId)
        assertTrue(LocalMemorySearch(db, fakeEmbedding, timeProvider).search(
            com.littlekitty.xiaokele.core.database.memory.MemoryQuery(text = "来源偏好"),
        ).isEmpty())
        assertEquals(1, processor.reconcileDeletedSources())
        assertEquals(MemoryStatuses.ARCHIVED, db.memoryDao().byId(memoryId)?.status)
    }

    private fun processorWithReview(review: MemoryReviewResult): MemoryAnalysisProcessor =
        MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS),
            memoryRepository = memoryRepo,
            userTraitRepository = traitRepo,
            behaviorTrainer = behaviorTrainer,
            embeddingService = fakeEmbedding,
            timeProvider = timeProvider,
            memoryReviewGateway = MemoryReviewGateway { review },
        )

    /** Seeds the USER/ASSISTANT pair the memory review treats as usable evidence. */
    private suspend fun seedTurn(userId: String, assistantId: String, userContent: String) {
        val now = timeProvider.now().toEpochMilli()
        db.messageDao().insert(
            MessageEntity(
                id = userId,
                role = "USER",
                content = userContent,
                createdAt = now,
                status = MessageStatuses.COMPLETED,
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                id = assistantId,
                role = "ASSISTANT",
                content = "记住了。",
                createdAt = now + 1,
                status = MessageStatuses.COMPLETED,
            ),
        )
    }

    private fun pipelineWith(processor: MemoryAnalysisProcessor): FrontChatPipeline {
        val hook = object : MemoryAnalysisHook {
            override fun enqueue(request: MemoryAnalysisRequest) {
                runBlocking {
                    val outcome = processor.analyze(request)
                    check(outcome is MemoryAnalysisOutcome.Failure || outcome is MemoryAnalysisOutcome.Applied)
                }
            }
        }
        return FrontChatPipeline(
            db = db,
            modelService = fakeModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            memoryAnalysisHook = hook,
        )
    }

    private class OneInvalidThenValidMemoryEvaluator : MemoryEvaluator {
        override val serviceName: String = "test_memory_evaluator"
        var calls: Int = 0
        var repairInstruction: String? = null

        override suspend fun evaluate(request: MemoryEvaluationRequest): MemoryEvaluationResult {
            calls += 1
            repairInstruction = request.formatRepairInstruction
            if (calls == 1) {
                return MemoryEvaluationResult.Success(
                    """
                    {"memory_candidates":[{"type":"USER_PREFERENCE","content":"测试用户不喜欢被催","importance":0.8,"confidence":0.9,"validity":"LONG_TERM","emotion_at_event":"略烦躁"}],"user_trait_patches":[{"key":"reminder_preference","operation":"UPSERT","value":{"description":"不喜欢被催"},"confidence":0.8,"profile_layer":"DYNAMIC","evidence_memory_ids":["mem_pending"]}],"behavior_patches":[],"deactivate_memory_ids":[],"pattern_observations":[]}
                    """.trimIndent(),
                )
            }
            return MemoryEvaluationResult.Success(
                JsonCodec.encode(
                    MemoryEvaluation(
                        memoryCandidates = listOf(
                            MemoryCandidate(
                                type = MemoryTypeNames.USER_PREFERENCE,
                                content = "测试用户不喜欢被催",
                                importance = 0.8,
                                confidence = 0.9,
                                validity = MemoryValidityNames.LONG_TERM,
                                evidenceMessageIds = listOf(request.userMessage.id),
                                sourceMessageId = request.userMessage.id,
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}
