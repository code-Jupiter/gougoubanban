package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.protocol.AgentActionNames
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.network.model.FakeModelMode
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.provider.CompanionFallback
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
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
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FrontChatPipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var fakeModel: FakeModelService
    private lateinit var pipeline: FrontChatPipeline
    private lateinit var timeProvider: FixedTimeProvider

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = FixedTimeProvider(Instant.parse("2026-08-16T10:00:00Z"))
        fakeModel = FakeModelService(FakeModelMode.SUCCESS)
        CharacterCoreBootstrap.ensureSeeded(db, timeProvider.now().toEpochMilli())
        pipeline = FrontChatPipeline(
            db = db,
            modelService = fakeModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun successPersistsMessagesActionPlanExecutionsAndPetState() = runBlocking {
        val outcome = pipeline.send("测试用户今天好累")

        assertTrue(outcome is ChatSendOutcome.Success)
        val success = outcome as ChatSendOutcome.Success

        val userMessage = db.messageDao().byId(success.userMessageId)
        val assistantMessage = db.messageDao().byId(success.assistantMessageId)
        assertEquals(MessageStatuses.COMPLETED, userMessage?.status)
        assertEquals("USER", userMessage?.role)
        assertEquals("ASSISTANT", assistantMessage?.role)
        assertEquals("default_idle", assistantMessage?.animationId)
        assertTrue(assistantMessage?.content.orEmpty().contains("测试用户"))

        val event = db.eventDao().byId(userMessage?.sourceEventId.orEmpty())
        assertEquals(EventStatuses.COMPLETED, event?.status)
        val plan = db.actionPlanDao().forEvent(event?.id.orEmpty()).single()
        assertEquals(ActionPlanStatuses.COMPLETED, plan.status)
        assertTrue(db.actionExecutionDao().forActionPlan(plan.id).all { it.status == "COMPLETED" })
        assertEquals("EXPECTING", db.petStateDao().get()?.stateId)
    }

    @Test
    fun idleFollowUpPersistsOneAssistantReplyWithoutSyntheticUserMessage() = runBlocking {
        val first = pipeline.send("今天有点不知道聊什么") as ChatSendOutcome.Success
        val userCountBefore = db.messageDao().allAscending().count { it.role == "USER" }

        val followUp = pipeline.sendIdleFollowUp(first.assistantMessageId) as ChatSendOutcome.Success
        val followUpMessage = db.messageDao().byId(followUp.assistantMessageId)
        val event = db.eventDao().byId(followUpMessage?.sourceEventId.orEmpty())

        assertEquals(userCountBefore, db.messageDao().allAscending().count { it.role == "USER" })
        assertEquals(3, db.messageDao().count())
        assertEquals("IDLE_FOLLOW_UP", event?.type)
        assertEquals("CHAT_IDLE_FOLLOW_UP", event?.source)
        assertTrue(followUpMessage?.content.orEmpty().contains("十分钟"))
        assertEquals(EventStatuses.COMPLETED, event?.status)

        val repeated = pipeline.sendIdleFollowUp(first.assistantMessageId) as ChatSendOutcome.Success
        assertEquals(followUp.assistantMessageId, repeated.assistantMessageId)
        assertEquals(3, db.messageDao().count())
    }

    @Test
    fun idleFollowUpFailureDoesNotCreateAssistantMessage() = runBlocking {
        val first = pipeline.send("先说一句") as ChatSendOutcome.Success
        fakeModel.mode = FakeModelMode.FAILURE

        val failure = pipeline.sendIdleFollowUp(first.assistantMessageId)

        assertTrue(failure is ChatSendOutcome.Failure)
        assertEquals(2, db.messageDao().count())
        assertEquals(1, db.eventDao().countByStatus(EventStatuses.FAILED))
    }

    @Test
    fun cancelledIdleFollowUpCannotRemainReceivedOrProcessing() = runBlocking {
        val blockingModel = BlockingSecondRequestModelService()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = blockingModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )
        val first = pipeline.send("先完成首条真实回复") as ChatSendOutcome.Success

        val job = launch { pipeline.sendIdleFollowUp(first.assistantMessageId) }
        blockingModel.secondRequestStarted.await()
        job.cancelAndJoin()

        val event = db.eventDao().byStatuses(listOf(EventStatuses.FAILED))
            .single { it.type == FrontChatPipeline.EVENT_TYPE_IDLE_FOLLOW_UP }
        assertEquals(EventStatuses.FAILED, event.status)
        assertEquals(1, event.attemptCount)
        assertEquals(ErrorCode.MODEL_FAILURE.name, event.lastErrorCode)
        assertEquals(FrontChatPipeline.IDLE_FOLLOW_UP_CANCELLED, event.lastErrorDetail)
        assertEquals(2, db.messageDao().count())
    }

    @Test
    fun cancelledUserSendCannotBeCompletedByLateModelResult() = runBlocking {
        val delayedModel = DelayedUserRequestModelService()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = delayedModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        var outcome: ChatSendOutcome? = null
        val job = launch { outcome = pipeline.send("取消这条慢回复") }
        delayedModel.requestStarted.await()
        assertTrue(pipeline.cancelCurrentUserAttempt())
        delayedModel.release.complete(Unit)
        job.join()

        assertTrue(outcome is ChatSendOutcome.Failure)
        assertEquals(MessageStatuses.FAILED, db.messageDao().allAscending().single().status)
        assertEquals(0, db.actionPlanDao().count())
    }

    @Test
    fun newUserMessageStartsImmediatelyAndSupersedesOlderAttempt() = runBlocking {
        val model = SuccessorModelService()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = model,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        var firstOutcome: ChatSendOutcome? = null
        val firstJob = launch { firstOutcome = pipeline.send("第一条慢消息") }
        model.firstRequestStarted.await()

        val secondOutcome = withTimeout(1_000) {
            async { pipeline.send("第二条立即消息") }.await()
        }
        assertTrue(secondOutcome is ChatSendOutcome.Success)
        assertEquals(
            MessageStatuses.FAILED,
            db.messageDao().allAscending().single { it.content == "第一条慢消息" }.status,
        )

        model.releaseFirst.complete(Unit)
        firstJob.join()
        assertTrue(firstOutcome is ChatSendOutcome.Failure)
        assertEquals(1, db.messageDao().allAscending().count { it.role == "ASSISTANT" })
        assertEquals(
            MessageStatuses.COMPLETED,
            db.messageDao().allAscending().single { it.content == "第二条立即消息" }.status,
        )
    }

    @Test
    fun queuedStopForOlderAttemptCannotCancelNewerMessage() = runBlocking {
        val model = SuccessorModelService()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = model,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        val firstJob = async { pipeline.send("第一条慢消息") }
        model.firstRequestStarted.await()
        val firstMessageId = db.messageDao().allAscending().single { it.content == "第一条慢消息" }.id
        val second = pipeline.send("第二条立即消息")

        assertTrue(second is ChatSendOutcome.Success)
        assertFalse(pipeline.cancelCurrentUserAttempt(firstMessageId))
        model.releaseFirst.complete(Unit)
        assertTrue(firstJob.await() is ChatSendOutcome.Failure)
        assertEquals(
            MessageStatuses.COMPLETED,
            db.messageDao().allAscending().single { it.content == "第二条立即消息" }.status,
        )
    }

    @Test
    fun userSendInvalidatesIdleFollowUpBeforeItCanBePersisted() = runBlocking {
        val delayedModel = DelayedSecondRequestModelService()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = delayedModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )
        val first = pipeline.send("先完成首条真实回复") as ChatSendOutcome.Success

        val followUpJob = launch { pipeline.sendIdleFollowUp(first.assistantMessageId) }
        delayedModel.secondRequestStarted.await()
        pipeline.cancelActiveIdleFollowUps()
        delayedModel.releaseSecondRequest.complete(Unit)
        followUpJob.join()

        assertEquals(2, db.messageDao().count())
        assertEquals(1, db.actionPlanDao().count())
        assertEquals(1, db.eventDao().countByStatus(EventStatuses.FAILED))
        assertEquals(1, db.messageDao().allAscending().count { it.role == "ASSISTANT" })

        val nextSend = pipeline.send("测试用户已经主动接话")

        assertTrue(nextSend is ChatSendOutcome.Success)
        assertEquals(4, db.messageDao().count())
    }

    @Test
    fun rejectedDraftDeliversLastDraftAndKeepsNormalMemoryEligibility() = runBlocking {
        val core = db.characterCoreDao().latest()!!
        db.characterCoreDao().upsert(core.copy(contentJson = """{"name":"测试犬乙","persona_anchor":"热情活泼"}"""))
        var calls = 0
        var memoryJobs = 0
        val responses = ArrayDeque(listOf(
            """{"goal":"回应用户","purpose":"DIRECT"}""",
            "REJECTED_PRIVATE_DRAFT",
            """{"reviewKind":"FINAL_REPLY","verdict":"REJECT","reasonCode":"PRIVACY_OR_INJECTION"}""",
        ))
        val gateway = object : com.littlekitty.xiaokele.provider.TaskModelGateway {
            override suspend fun generate(request: com.littlekitty.xiaokele.provider.TaskModelRequest,
                budget: com.littlekitty.xiaokele.provider.GenerationBudget): com.littlekitty.xiaokele.provider.TaskModelResult {
                calls++
                return com.littlekitty.xiaokele.provider.TaskModelResult.Success(responses.removeFirst(),
                    com.littlekitty.xiaokele.provider.TaskModelRouter().resolve(request.task),
                    com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    com.littlekitty.xiaokele.provider.ModelCallProvenance(com.littlekitty.xiaokele.provider.ModelProvider.BAILIAN, "fake"))
            }
        }
        pipeline = FrontChatPipeline(db, fakeModel, ChatContextBuilder(db, timeProvider), CatalogAnimationAdapter(), timeProvider,
            memoryAnalysisHook = object : com.littlekitty.xiaokele.memory.MemoryAnalysisHook {
                override fun enqueue(request: com.littlekitty.xiaokele.memory.MemoryAnalysisRequest) { memoryJobs++ }
            }, replyCoordinator = com.littlekitty.xiaokele.provider.ReplyCoordinator(gateway))
        val result = pipeline.send("请记住这件事") as ChatSendOutcome.Success
        val assistant = db.messageDao().byId(result.assistantMessageId)!!
        assertEquals("REJECTED_PRIVATE_DRAFT", assistant.content)
        assertNull(assistant.errorCode)
        assertEquals(1, memoryJobs)
        assertEquals(3, calls)
        assertEquals(3, db.eventDao().byId(assistant.sourceEventId!!)!!.generationRequestCount)
    }

    @Test
    fun contextBuildFailureStillUsesConfirmedPersonaWithoutCallingModel() = runBlocking {
        val core = db.characterCoreDao().latest()!!
        db.characterCoreDao().upsert(core.copy(contentJson = """{"name":"测试犬丙","persona_anchor":"沉稳话少"}"""))
        val memorySearch = object : com.littlekitty.xiaokele.core.database.memory.MemorySearch {
            override suspend fun search(query: com.littlekitty.xiaokele.core.database.memory.MemoryQuery):
                List<com.littlekitty.xiaokele.core.database.memory.MemoryHit> = error("lookup failed")
        }
        pipeline = FrontChatPipeline(db, fakeModel, ChatContextBuilder(db, timeProvider, memorySearch),
            CatalogAnimationAdapter(), timeProvider)
        val result = pipeline.send("你好") as ChatSendOutcome.Success
        val assistant = db.messageDao().byId(result.assistantMessageId)!!
        assertTrue(assistant.content.startsWith("测试犬丙 在。"))
        assertEquals(0, db.eventDao().byId(assistant.sourceEventId!!)!!.generationRequestCount)
        assertEquals(CompanionFallback.MESSAGE_MARKER, assistant.errorCode)
    }

    @Test
    fun providerFailurePublishesOfflineCompanionReplyWithoutRetryingModel() = runBlocking {
        fakeModel.mode = FakeModelMode.FAILURE
        val failure = pipeline.send("第一次发送")

        assertTrue(failure is ChatSendOutcome.Success)
        val delivered = failure as ChatSendOutcome.Success
        val userMessage = db.messageDao().byId(delivered.userMessageId)
        assertEquals(MessageStatuses.COMPLETED, userMessage?.status)
        val reply = db.messageDao().byId(delivered.assistantMessageId)!!
        assertEquals(CompanionFallback.MESSAGE_MARKER, reply.errorCode)
        assertTrue(reply.content.contains("不想随便编"))
        assertEquals(2, db.messageDao().count())
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId(userMessage?.sourceEventId.orEmpty())?.status)
        val nextContext = ChatContextBuilder(db, timeProvider).buildFor(userMessage!!)
        assertFalse(nextContext.event.toString().contains(reply.content))
    }

    @Test
    fun invalidJsonProducesOnlyALocalCompanionReply() = runBlocking {
        fakeModel.mode = FakeModelMode.INVALID_JSON

        val outcome = pipeline.send("测试非法输出")

        assertTrue(outcome is ChatSendOutcome.Success)
        val reply = db.messageDao().byId((outcome as ChatSendOutcome.Success).assistantMessageId)!!
        assertEquals(CompanionFallback.MESSAGE_MARKER, reply.errorCode)
        assertEquals(2, db.messageDao().count())
    }

    @Test
    fun nonWhitelistActionIsRejectedAsInvalidOutput() = runBlocking {
        fakeModel.mode = FakeModelMode.UNSUPPORTED_ACTION

        val outcome = pipeline.send("测试非白名单")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(CompanionFallback.MESSAGE_MARKER,
            db.messageDao().byId((outcome as ChatSendOutcome.Success).assistantMessageId)?.errorCode)
        assertEquals(2, db.messageDao().count())
    }

    @Test
    fun unknownAnimationIdIsDegradedToDefaultWithoutFailingReply() = runBlocking {
        pipeline = FrontChatPipeline(
            db = db,
            modelService = RawDecisionModelService(validDecisionWithUnknownAnimation()),
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        val outcome = pipeline.send("未知动画降级测试")

        assertTrue(outcome is ChatSendOutcome.Success)
        val assistant = db.messageDao().byId((outcome as ChatSendOutcome.Success).assistantMessageId)
        assertEquals("default_idle", assistant?.animationId)
        assertEquals(MessageStatuses.COMPLETED, db.messageDao().byId(outcome.userMessageId)?.status)
    }

    @Test
    fun frontChatChannelRejectsBackgroundActionAndTodoMutations() = runBlocking {
        pipeline = FrontChatPipeline(
            db = db,
            modelService = RawDecisionModelService(decisionWithDeferredActionAndTodoMutation()),
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        val outcome = pipeline.send("测试后台 action 不得进入前台")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(CompanionFallback.MESSAGE_MARKER,
            db.messageDao().byId((outcome as ChatSendOutcome.Success).assistantMessageId)?.errorCode)
        assertEquals(2, db.messageDao().count())
        assertEquals(0, db.todoDao().count())
        assertEquals(1, db.actionPlanDao().count())
    }

    @Test
    fun frontChatClaimsReferencedTodoAtomicallySoBackgroundCannotDuplicate() = runBlocking {
        val todoManager = com.littlekitty.xiaokele.core.database.repository.TodoManager(db, db.todoDao())
        val coordinator = com.littlekitty.xiaokele.core.database.repository.TodoClaimCoordinator(todoManager)
        val todo = todoManager.create(
            type = "CHAT_OPPORTUNISTIC",
            now = timeProvider.now().toEpochMilli(),
        )
        pipeline = FrontChatPipeline(
            db = db,
            modelService = RawDecisionModelService(decisionClaimingTodo(todo.id)),
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            todoClaimCoordinator = coordinator,
        )

        val outcome = pipeline.send("自然插入待聊话题")

        assertTrue(outcome is ChatSendOutcome.Success)
        val assistant = db.messageDao().byId((outcome as ChatSendOutcome.Success).assistantMessageId)
        assertEquals(todo.id, assistant?.sourceTodoId)
        assertEquals("CLAIMED", db.todoDao().byId(todo.id)?.status)
        assertNull(coordinator.claimForBackground(todo.id, "evt_00000000000000000000000000000001", timeProvider.now().toEpochMilli()))
    }

    @Test
    fun nativeToolRoundTripExecutesToolAndFeedsResultBackToModel() = runBlocking {
        val turnsSeen = mutableListOf<List<FrontChatToolTurn>>()
        var modelRound = 0
        val nativeModel = object : FrontChatModelService {
            override suspend fun generateFrontChat(
                context: ContextPack,
                turns: List<FrontChatToolTurn>,
            ): FrontChatModelResult {
                turnsSeen += turns.toList()
                return if (modelRound++ == 0) {
                    val assistantMessage = buildJsonObject {
                        put("role", "assistant")
                        put("content", "")
                        put("tool_calls", buildJsonArray {})
                    }
                    FrontChatModelResult.ToolCalls(
                        calls = listOf(
                            FrontChatToolCall(
                                id = "call_weather_1",
                                name = "query_weather",
                                arguments = buildJsonObject { put("profile", "CORE") },
                                assistantMessage = assistantMessage,
                            ),
                        ),
                    )
                } else {
                    FrontChatModelResult.Final(validDecisionWithUnknownAnimation().replace("dog_not_imported", "default_idle"))
                }
            }
        }
        val executedCalls = mutableListOf<FrontChatToolCall>()
        val executor = object : LocalChatToolExecutor {
            override suspend fun execute(call: FrontChatToolCall) = buildJsonObject {
                executedCalls.add(call)
                put("ok", true)
                put("temperature_c", 23)
            }
        }
        pipeline = FrontChatPipeline(
            db = db,
            modelService = fakeModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            frontChatModelService = nativeModel,
            localChatToolExecutor = executor,
        )

        val outcome = pipeline.send("家里现在天气怎么样？")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(1, executedCalls.size)
        assertEquals("query_weather", executedCalls.single().name)
        assertEquals(2, turnsSeen.size)
        assertTrue(turnsSeen[1].first() is FrontChatToolTurn.AssistantToolCalls)
        val toolResult = turnsSeen[1].filterIsInstance<FrontChatToolTurn.ToolResult>().single()
        assertEquals("call_weather_1", toolResult.call.id)
        assertEquals("true", toolResult.result["ok"]?.toString())
    }

    @Test
    fun nativeToolOverflowReturnsStructuredResultAndDoesNotExecuteEveryCall() = runBlocking {
        val turnsSeen = mutableListOf<List<FrontChatToolTurn>>()
        var modelRound = 0
        val assistantMessage = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray {
                repeat(4) { index ->
                    add(buildJsonObject {
                        put("id", "call_${index + 1}")
                        put("type", "function")
                        put("function", buildJsonObject { put("name", "query_weather") })
                    })
                }
            })
        }
        val nativeModel = object : FrontChatModelService {
            override suspend fun generateFrontChat(
                context: ContextPack,
                turns: List<FrontChatToolTurn>,
            ): FrontChatModelResult {
                turnsSeen += turns.toList()
                return if (modelRound++ == 0) {
                    FrontChatModelResult.ToolCalls(
                        (0 until 4).map { index ->
                            FrontChatToolCall(
                                id = "call_${index + 1}",
                                name = "query_weather",
                                arguments = buildJsonObject {
                                    put("profile", listOf("CORE", "DAILY_BRIEF", "FULL", "SHIFT_COMMUTE")[index])
                                },
                                assistantMessage = assistantMessage,
                                assistantToolCallIndex = index,
                            )
                        },
                    )
                } else {
                    FrontChatModelResult.Final(validDecisionWithUnknownAnimation().replace("dog_not_imported", "default_idle"))
                }
            }
        }
        val executedCalls = mutableListOf<FrontChatToolCall>()
        pipeline = FrontChatPipeline(
            db = db,
            modelService = fakeModel,
            contextBuilder = ChatContextBuilder(db, timeProvider),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            frontChatModelService = nativeModel,
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall) = buildJsonObject {
                    executedCalls += call
                    put("ok", true)
                }
            },
        )

        val outcome = pipeline.send("帮我查一下天气")

        assertTrue(outcome is ChatSendOutcome.Success)
        assertEquals(2, executedCalls.size)
        val overflow = turnsSeen[1].filterIsInstance<FrontChatToolTurn.ToolResult>()
            .single { it.call.id == "call_4" }
        assertEquals(
            ToolCallBudget.ROUND_LIMIT_REACHED,
            (overflow.result["result"] as? kotlinx.serialization.json.JsonObject)
                ?.get("error_code")
                ?.toString()
                ?.trim('"'),
        )
    }

    @Test
    fun blankInputIsRejectedWithoutPersistence() = runBlocking {
        val outcome = pipeline.send("   ")

        assertTrue(outcome is ChatSendOutcome.Failure)
        assertEquals(ErrorCode.INVALID_ARGUMENT, (outcome as ChatSendOutcome.Failure).error.code)
        assertEquals(0, db.messageDao().count())
    }

    private fun validDecisionWithUnknownAnimation(): String {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject {
                        put("content", "测试用户，在呢")
                        put("animation_id", "dog_not_imported")
                    },
                ),
            ),
            animationId = "dog_not_imported",
        )
        return com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision)
    }

    private fun decisionWithDeferredActionAndTodoMutation(): String {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject { put("content", "测试用户，在呢") },
                ),
                AgentOutput(
                    action = AgentActionNames.SEND_NOTIFICATION,
                    payload = buildJsonObject { put("title", "测试通知") },
                ),
            ),
            todoMutations = listOf(
                com.littlekitty.xiaokele.core.common.protocol.TodoMutation(
                    operation = "CREATE",
                    todo = buildJsonObject { put("type", "DISCUSS_LATER") },
                ),
            ),
        )
        return com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision)
    }

    private fun decisionClaimingTodo(todoId: String): String {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject {
                        put("content", "测试用户，可乐顺便说：今天这个话题要聊一聊")
                        put("todo_id", todoId)
                    },
                ),
            ),
        )
        return com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision)
    }

    private class RawDecisionModelService(
        private val raw: String,
    ) : ModelService {
        override val serviceName: String = "raw_test_model"
        override suspend fun generate(context: ContextPack): ModelServiceResult =
            ModelServiceResult.Success(raw)
    }

    private class BlockingSecondRequestModelService : ModelService {
        override val serviceName: String = "blocking_second_request"
        val secondRequestStarted = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            calls += 1
            if (calls == 2) {
                secondRequestStarted.complete(Unit)
                awaitCancellation()
            }
            return ModelServiceResult.Success(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = AgentActionNames.REPLY_CHAT,
                                payload = buildJsonObject { put("content", "测试用户，测试小狗在。") },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class DelayedUserRequestModelService : ModelService {
        override val serviceName: String = "delayed_user_request"
        val requestStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            requestStarted.complete(Unit)
            release.await()
            return ModelServiceResult.Success(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = AgentActionNames.REPLY_CHAT,
                                payload = buildJsonObject { put("content", "迟到的回复") },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class SuccessorModelService : ModelService {
        override val serviceName: String = "successor_model"
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            calls += 1
            if (calls == 1) {
                firstRequestStarted.complete(Unit)
                releaseFirst.await()
            }
            return ModelServiceResult.Success(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = AgentActionNames.REPLY_CHAT,
                                payload = buildJsonObject { put("content", if (calls == 1) "旧回复" else "新回复") },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class DelayedSecondRequestModelService : ModelService {
        override val serviceName: String = "delayed_second_request"
        val secondRequestStarted = CompletableDeferred<Unit>()
        val releaseSecondRequest = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            calls += 1
            if (calls == 2) {
                secondRequestStarted.complete(Unit)
                releaseSecondRequest.await()
            }
            return ModelServiceResult.Success(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = AgentActionNames.REPLY_CHAT,
                                payload = buildJsonObject { put("content", "测试用户，测试小狗在。") },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private class FixedTimeProvider(
        private val instant: Instant,
    ) : TimeProvider {
        private val clock = Clock.fixed(instant, ZoneId.of("UTC"))
        override fun now(): Instant = Instant.now(clock)
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }
}
