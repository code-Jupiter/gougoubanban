package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.chat.LocalChatToolExecutor
import com.littlekitty.xiaokele.chat.FrontChatToolCall
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyCoordinatorTest {
    @Test
    fun repairPlusFullDecisionRevisionNeverExceedsEightRequests() = runBlocking {
        val malformed = """{"goal":"回答","purpose":"PLAN",}"""
        val responses = ArrayDeque(listOf(
            """{"goal":"回答","purpose":"PLAN"}""", // repair
            """{"replyGoal":"回答"}""", "初稿",
            """{"reviewKind":"FINAL_REPLY","verdict":"REVISE","reasonCode":"TASK_MISMATCH","issues":[{"type":"DECISION","minimumChange":"回应原问题"}]}""",
            """{"replyGoal":"回应原问题"}""", "修订稿",
            """{"reviewKind":"FINAL_REPLY","verdict":"REVISE","reasonCode":"TASK_MISMATCH","issues":[{"type":"DECISION","minimumChange":"仍有遗漏"}]}""",
        ))
        var calls = 0
        var gates = 0
        val delegate = fakeGateway(responses)
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                calls++
                return if (calls == 1) TaskModelResult.Failure(AppError(ErrorCode.MODEL_OUTPUT_INVALID), recoverableContent = malformed)
                    else delegate.generate(request, budget)
            }
        }
        val result = ReplyCoordinator(gateway).generate(context(), requestGate = { gates++; true })
        assertTrue(result is ModelServiceResult.Success)
        assertTrue((result as ModelServiceResult.Success).rawDecisionJson.contains("修订稿"))
        assertTrue(calls == 8 && gates == 8)
        assertTrue(com.littlekitty.xiaokele.chat.FrontChatPipeline.MAX_FOREGROUND_GENERATION_REQUESTS == 8)
    }

    @Test
    fun lightweightIntakeSkipsB1ButReviewsHypotheticalDogInteraction() = runBlocking {
        val tasks = mutableListOf<TaskKind>()
        val outputs = ArrayDeque(listOf(
            """{"goal":"回应用户","purpose":"DIRECT","emotionCues":["可能委屈"]}""",
            "你的眼睛是不是红啦？哎呀，让我来给你揉揉。",
            """{"reviewKind":"FINAL_REPLY","verdict":"PASS","reasonCode":"NONE"}""",
        ))
        val delegate = fakeGateway(outputs)
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                assertTrue(request.userPayload.contains("\"writeOperations\":false"))
                return delegate.generate(request, budget)
            }
        }
        val result = ReplyCoordinator(gateway).generate(context())
        assertTrue(result is ModelServiceResult.Success)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW))
    }

    @Test
    fun memorySupplementRunsOnlyOnceEvenWhenReviewRequestsMoreInformation() = runBlocking {
        var lookups = 0
        val lookup = ReplyMemoryLookup {
            lookups++
            kotlinx.serialization.json.JsonArray(emptyList())
        }
        val outputs = ArrayDeque(listOf(
            """{"goal":"找上次方案","purpose":"PLAN","memoryQuery":"最新已确认方案"}""",
            """{"replyGoal":"说明没有查到"}""",
            "还没有查到。",
            """{"reviewKind":"FINAL_REPLY","verdict":"REVISE","reasonCode":"MISSING_INFORMATION","issues":[{"type":"SOURCE","minimumChange":"说明不确定"}]}""",
            """{"replyGoal":"说明缺少资料"}""",
            "这件事我还不确定。",
            """{"reviewKind":"FINAL_REPLY","verdict":"PASS"}""",
        ))
        assertTrue(ReplyCoordinator(fakeGateway(outputs), memoryLookup = lookup).generate(context()) is ModelServiceResult.Success)
        assertTrue(lookups == 1)
    }

    @Test
    fun deadlineCancelsAnInFlightRequestRatherThanStartingAnother() = runBlocking {
        var calls = 0
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                calls++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val result = ReplyCoordinator(gateway, attemptTimeoutMillis = 30).generate(context())
        assertTrue(result is ModelServiceResult.Failure)
        assertTrue((result as ModelServiceResult.Failure).error.technicalDetail == "reply_attempt_timeout")
        assertTrue(calls == 1)
    }

    @Test
    fun regenerationAllowanceIsSharedAcrossStages() = runBlocking {
        val tasks = mutableListOf<TaskKind>()
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                if (tasks.size != 2) return TaskModelResult.Failure(AppError(ErrorCode.MODEL_OUTPUT_INVALID))
                return fakeGateway(ArrayDeque(listOf("""{"goal":"回答","purpose":"DIRECT"}"""))).generate(request, budget)
            }
        }
        assertTrue(ReplyCoordinator(gateway).generate(context()) is ModelServiceResult.Failure)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE, TaskKind.A_INTAKE, TaskKind.B2_EXPRESSION))
    }

    @Test
    fun completeMalformedJsonUsesOneLosslessRepairCall() = runBlocking {
        val malformed = """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","directResponseBrief":{"replyGoal":"回答","mustAddress":[],"mayExpress":[],"internalOnly":[]},}"""
        val repaired = malformed.replace("},}", "}}")
        val stages = mutableListOf<String>()
        val outputs = ArrayDeque(listOf(repaired, "你好。"))
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                stages += if (request.redactDiagnostics) "REPAIR" else request.task.name
                if (stages.size == 1) return TaskModelResult.Failure(
                    AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "structured_json_missing"),
                    recoverableContent = malformed,
                )
                return TaskModelResult.Success(
                    outputs.removeFirst(), TaskModelRouter().resolve(request.task),
                    com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        }
        assertTrue(ReplyCoordinator(gateway).generate(context()) is ModelServiceResult.Success)
        assertTrue(stages == listOf("A_INTAKE", "REPAIR", "B2_EXPRESSION"))
    }

    @Test
    fun lossyRepairIsDiscardedAndOnlyOriginalStageIsRegenerated() = runBlocking {
        val malformed = """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER",}"""
        val stages = mutableListOf<String>()
        val outputs = ArrayDeque(listOf(
            """{"task":"A_INTAKE","purpose":"DIRECT","action":"NO_ACTION"}""", // altered meaning: reject repair
            """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","directResponseBrief":{"replyGoal":"回答","mustAddress":[],"mayExpress":[],"internalOnly":[]}}""",
            "你好。",
        ))
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                stages += if (request.redactDiagnostics) "REPAIR" else request.task.name
                if (stages.size == 1) return TaskModelResult.Failure(
                    AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "structured_json_missing"),
                    recoverableContent = malformed,
                )
                return TaskModelResult.Success(outputs.removeFirst(), TaskModelRouter().resolve(request.task),
                    com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"))
            }
        }
        assertTrue(ReplyCoordinator(gateway).generate(context()) is ModelServiceResult.Success)
        assertTrue(stages == listOf("A_INTAKE", "REPAIR", "A_INTAKE", "B2_EXPRESSION"))
    }

    @Test
    fun reviewCannotAuthorizeAWriteThatNeverRan() = runBlocking {
        val outputs = ArrayDeque(listOf(
            """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","boundaries":["只回复一句"],"directResponseBrief":{"replyGoal":"回应","mustAddress":[],"mayExpress":[],"internalOnly":[]}}""",
            "已经设好啦。",
            """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":[]}""",
        ))
        assertTrue(ReplyCoordinator(fakeGateway(outputs)).generate(context()) is ModelServiceResult.Failure)
    }

    @Test
    fun quotedOrNegatedCompletionTextIsNotTreatedAsACompletedWrite() = runBlocking {
        val intake = """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","directResponseBrief":{"replyGoal":"解释","mustAddress":[],"mayExpress":[],"internalOnly":[]}}"""
        val pass = """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":[]}"""
        assertTrue(ReplyCoordinator(fakeGateway(ArrayDeque(listOf(intake, "我不能说已经设好，因为没有执行结果。", pass)))).generate(context())
            is ModelServiceResult.Success)
        assertTrue(ReplyCoordinator(fakeGateway(ArrayDeque(listOf(intake, "“已保存”只是状态标签。", pass)))).generate(context())
            is ModelServiceResult.Success)
    }
    @Test
    fun directBriefAcceptsMiniMaxCompactStringExpressionItems() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"route":"DIRECT","directResponseBrief":{"replyGoal":"回应当前用户","supportMode":"BRIEF_ACK","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[],"mayExpress":["温和回应"],"internalOnly":[],"uncertainties":[],"behaviorBoundaries":[],"constructScope":[]}}""",
                "你好呀。",
            ),
        )

        val result = ReplyCoordinator(fakeGateway(outputs)).generate(context())

        assertTrue(result is ModelServiceResult.Success)
    }

    /**
     * Live Bailian `deepseek-v4-flash` returned object elements for the brief's
     * `uncertainties` even though the contract declares a string array, which
     * failed the whole A response with
     * `$.directResponseBrief.uncertainties[0]`.  The compact-form normalizer must
     * collapse those to their text instead of losing the turn.
     */
    @Test
    fun directBriefAcceptsObjectElementsForDeclaredStringArrays() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","supportLevel":"BRIEF_ACK","action":"ANSWER","uncertainties":[{"text":"对方是否在忙"}],"materials":[{"text":"当前上下文材料"}],"directResponseBrief":{"replyGoal":"回应当前用户","supportMode":"BRIEF_ACK","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[],"mayExpress":[],"internalOnly":[],"uncertainties":[{"text":"对方此刻的心情","evidenceId":"UNKNOWN","kind":"INFERRED"}],"behaviorBoundaries":[{"text":"不追问"}],"constructScope":[{"text":"只谈今天"}],"lengthBudget":800,"candidates":[]}}""",
                "你好呀。",
            ),
        )

        val result = ReplyCoordinator(fakeGateway(outputs)).generate(context())

        assertTrue(result is ModelServiceResult.Success)
    }

    @Test
    fun proactiveNoActionIsACompleteTerminalWithoutHer() = runBlocking {
        val tasks = mutableListOf<TaskKind>()
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                return TaskModelResult.Success(
                    content = """{"task":"A_INTAKE","purpose":"DIRECT","action":"NO_ACTION","uncertainties":["没有值得打扰用户的理由"]}""",
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })

        val result = coordinator.generate(context(), entryPoint = ReplyEntryPoint.PROACTIVE)

        assertTrue(result is ModelServiceResult.NoAction)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE))
    }

    @Test
    fun foregroundNoActionFailsClosedInsteadOfSilentlyDroppingUserMessage() = runBlocking {
        val tasks = mutableListOf<TaskKind>()
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                return TaskModelResult.Success(
                    content = """{"task":"A_INTAKE","purpose":"DIRECT","action":"NO_ACTION"}""",
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })

        val result = coordinator.generate(context(), entryPoint = ReplyEntryPoint.FOREGROUND)

        assertTrue(result is ModelServiceResult.Failure)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE))
    }

    @Test
    fun idleFollowUpUsesFrontReplyChannelButMayChooseNoAction() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","action":"NO_ACTION","uncertainties":["没有自然的续话理由"]}""",
            ),
        )
        val coordinator = ReplyCoordinator(fakeGateway(outputs))
        val noAction = coordinator.generate(context(), entryPoint = ReplyEntryPoint.FOREGROUND_IDLE)
        assertTrue(noAction is ModelServiceResult.NoAction)

        val replyOutputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","directResponseBrief":{"replyGoal":"自然续话","supportMode":"BRIEF_ACK","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[],"mayExpress":[],"internalOnly":[],"uncertainties":[],"behaviorBoundaries":[],"constructScope":[],"lengthBudget":800,"candidates":[]}}""",
                "继续聊也可以。",
            ),
        )
        val reply = ReplyCoordinator(fakeGateway(replyOutputs)).generate(
            context(),
            entryPoint = ReplyEntryPoint.FOREGROUND_IDLE,
        )
        assertTrue(reply is ModelServiceResult.Success)
        assertTrue((reply as ModelServiceResult.Success).rawDecisionJson.contains("REPLY_CHAT"))
        assertTrue(!reply.rawDecisionJson.contains("SEND_PROACTIVE_MESSAGE"))
    }

    @Test
    fun executesAAllowlistedToolBeforeB1AndKeepsHerToolFree() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","supportLevel":"FOCUSED_SUPPORT","action":"ANSWER","toolRequests":[{"name":"query_location","arguments":{}}]}""",
                """{"replyGoal":"依据真实定位回答","mustAddress":[{"text":"当前定位结果","evidenceId":"tool_0_query_location","kind":"EVIDENCED"}],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
                "定位结果已核对。",
                """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":["tool_0_query_location"]}""",
            ),
        )
        var executed = 0
        val coordinator = ReplyCoordinator(
            gateway = fakeGateway(outputs),
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject {
                    executed++
                    assertTrue(call.name == "query_location")
                    return buildJsonObject { put("type", "tool_result"); put("tool_name", call.name); put("result", buildJsonObject { put("ok", true) }) }
                }
            },
        )
        val result = coordinator.generate(context())
        assertTrue(result is ModelServiceResult.Success)
        assertTrue(executed == 1)
    }

    @Test
    fun runsDirectAThenHerWithoutB1AndPublishesOnlyValidatedReply() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","supportLevel":"BRIEF_ACK","action":"ANSWER","materials":["当前上下文材料"],"directResponseBrief":{"replyGoal":"回应当前用户","supportMode":"BRIEF_ACK","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[{"text":"当前用户原话","evidenceId":"CURRENT","kind":"OBSERVED"}],"mayExpress":[],"internalOnly":[],"uncertainties":[],"behaviorBoundaries":[],"constructScope":[],"lengthBudget":800,"candidates":[]}}""",
                "具体回答，不泄露内部结构。",
            ),
        )
        val requests = mutableListOf<TaskModelRequest>()
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                requests += request
                return TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })
        val result = coordinator.generate(context())
        assertTrue(result is ModelServiceResult.Success)
        assertTrue(requests.map { it.task } == listOf(TaskKind.A_INTAKE, TaskKind.B2_EXPRESSION))
        assertTrue(requests.last().userPayload.contains("currentUserOriginal=当前用户原话"))
        assertTrue(requests.last().userPayload.contains("recentConversationOriginal="))
        assertTrue(requests.last().userPayload.contains("expressionLedger="))
        assertTrue(requests.last().userPayload.contains("近期已经提交的对话"))
        val raw = (result as ModelServiceResult.Success).rawDecisionJson
        assertTrue(raw.contains("REPLY_CHAT"))
        assertTrue(raw.contains("具体回答"))
        assertTrue(!raw.contains("replyGoal"))
    }

    @Test
    fun toolResultIsOrganizedByB1EvenIfIntakeSaysDirect() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","supportLevel":"FOCUSED_SUPPORT","action":"ANSWER","toolRequests":[{"name":"query_location","arguments":{}}],"directResponseBrief":{"replyGoal":"依据当前定位自然回应","supportMode":"FOCUSED_SUPPORT","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[{"text":"当前用户原话","evidenceId":"CURRENT","kind":"OBSERVED"}],"mayExpress":[],"internalOnly":[],"uncertainties":[],"behaviorBoundaries":[],"constructScope":[],"lengthBudget":800,"candidates":[]}}""",
                """{"replyGoal":"依据定位结果回应","mustAddress":[]}""",
                "定位已经核对好了。",
                """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":["tool_0_query_location"]}""",
            ),
        )
        val requests = mutableListOf<TaskModelRequest>()
        var executed = 0
        val coordinator = ReplyCoordinator(
            gateway = object : TaskModelGateway {
                override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                    requests += request
                    return TaskModelResult.Success(
                        content = outputs.removeFirst(),
                        snapshot = TaskModelRouter().resolve(request.task),
                        trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                        provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                    )
                }
            },
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject {
                    executed++
                    return buildJsonObject {
                        put("type", "tool_result")
                        put("tool_name", call.name)
                        put("result", buildJsonObject { put("ok", true) })
                    }
                }
            },
        )

        val result = coordinator.generate(context())

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertTrue(executed == 1)
        assertTrue(requests.map { it.task } == listOf(TaskKind.A_INTAKE, TaskKind.B1_DECISION, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW))
        assertTrue(requests[1].userPayload.contains("tool_name"))
    }

    @Test
    fun proactiveAPlannerCannotBypassSceneToolAllowlist() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","toolRequests":[{"name":"query_location","arguments":{}}],"directResponseBrief":{"replyGoal":"回应","supportMode":"BRIEF_ACK","communicativeAct":"ANSWER","continuation":"NONE","mustAddress":[],"mayExpress":[],"internalOnly":[],"uncertainties":[],"behaviorBoundaries":[],"constructScope":[],"lengthBudget":800,"candidates":[]}}""",
            ),
        )
        var executed = false
        val coordinator = ReplyCoordinator(
            gateway = fakeGateway(outputs),
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject {
                    executed = true
                    return buildJsonObject { put("ok", true) }
                }
            },
        )
        val context = context().copy(
            scenePolicy = buildJsonObject {
                put("automation_rule", buildJsonObject { put("allowed_tools", "query_weather") })
            },
        )

        val result = coordinator.generate(context, entryPoint = ReplyEntryPoint.PROACTIVE)

        assertTrue(result is ModelServiceResult.Failure)
        assertTrue(!executed)
    }

    @Test
    fun directWithoutAOwnedBriefRecoversThroughB1() = runBlocking {
        val requestedTasks = mutableListOf<TaskKind>()
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                requestedTasks += request.task
                val content = if (request.task == TaskKind.A_INTAKE) {
                    """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER"}"""
                } else {
                    """{"replyGoal":"回复当前用户","mustAddress":[],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}"""
                }
                return TaskModelResult.Success(
                    content = content,
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Failure)
        assertTrue(requestedTasks == listOf(TaskKind.A_INTAKE, TaskKind.B1_DECISION, TaskKind.B2_EXPRESSION, TaskKind.B2_EXPRESSION))
    }

    @Test
    fun expressionRevisionUsesExactlySixCallsAndStillRunsFinalReview() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER"}""",
                """{"replyGoal":"回答","mustAddress":[],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
                "初稿",
                """{"reviewKind":"FINAL_REPLY","verdict":"REVISE","issues":[{"location":"正文","type":"EXPRESSION","minimumChange":"更自然"}],"evidenceIds":[]}""",
                "修订稿",
                """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":[]}""",
            ),
        )
        val tasks = mutableListOf<TaskKind>()
        var reviewPayload = ""
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                if (request.task == TaskKind.C_REVIEW && reviewPayload.isBlank()) reviewPayload = request.userPayload
                return TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Success)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE, TaskKind.B1_DECISION, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW))
        assertTrue(reviewPayload.contains("candidate=初稿"))
        assertTrue(reviewPayload.contains("responseBrief="))
    }

    @Test
    fun decisionRevisionAfterPlanUsesReservedBudgetForB1HerAndReview() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER"}""",
                """{"replyGoal":"回答","mustAddress":[],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
                "初稿",
                """{"reviewKind":"FINAL_REPLY","verdict":"REVISE","issues":[{"location":"事实","type":"FACT","minimumChange":"缺少依据"}],"evidenceIds":[]}""",
                """{"replyGoal":"只说有依据的事实","mustAddress":[],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
                "修订稿",
                """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":[]}""",
            ),
        )
        val tasks = mutableListOf<TaskKind>()
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                tasks += request.task
                return TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
            }
        })
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Success)
        assertTrue(tasks == listOf(TaskKind.A_INTAKE, TaskKind.B1_DECISION, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW,
            TaskKind.B1_DECISION, TaskKind.B2_EXPRESSION, TaskKind.C_REVIEW))
    }

    @Test
    fun rejectsHerJsonLikeOutputBeforeCommit() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","action":"ANSWER"}""",
                """{"content":"not plain text"}""",
            ),
        )
        val coordinator = ReplyCoordinator(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult =
                TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )
        })
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Failure)
    }

    @Test
    fun rejectsEvidenceOutsideContextAllowlist() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER"}""",
                """{"replyGoal":"回答","mustAddress":[{"text":"未获准事实","evidenceId":"not-in-context","kind":"EVIDENCED"}],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
            ),
        )
        assertTrue(ReplyCoordinator(fakeGateway(outputs)).generate(context()) is ModelServiceResult.Failure)
    }

    @Test
    fun rejectsUnknownOrMissingToolArgumentsBeforeExecution() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER","toolRequests":[{"name":"query_weather","arguments":{"unknown":"x"}}]}""",
            ),
        )
        var executed = false
        val coordinator = ReplyCoordinator(
            gateway = fakeGateway(outputs),
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject {
                    executed = true
                    return buildJsonObject { put("ok", true) }
                }
            },
        )
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Failure)
        assertTrue(!executed)
    }

    @Test
    fun boundsOversizedToolResultBeforeItReachesDecisionModel() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER","toolRequests":[{"name":"query_location","arguments":{}}]}""",
                """{"replyGoal":"依据定位回答","mustAddress":[],"mayExpress":[],"internalOnly":[],"action":"ANSWER"}""",
                "纯文本回复",
                """{"reviewKind":"FINAL_REPLY","verdict":"PASS","issues":[],"evidenceIds":[]}""",
            ),
        )
        var b1Payload = ""
        val coordinator = ReplyCoordinator(
            gateway = object : TaskModelGateway {
                override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                    if (request.task == TaskKind.B1_DECISION) b1Payload = request.userPayload
                    return TaskModelResult.Success(
                        content = outputs.removeFirst(),
                        snapshot = TaskModelRouter().resolve(request.task),
                        trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                        provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                    )
                }
            },
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject =
                    buildJsonObject { put("payload", "x".repeat(20_000)) }
            },
        )
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Success)
        assertTrue(b1Payload.contains("tool_result_truncated"))
        assertTrue(b1Payload.length < 20_000)
    }

    @Test
    fun rejectsDurableCreativeMutationRequestedByIntake() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"PLAN","action":"ANSWER","toolRequests":[{"name":"mark_topic_discussed","arguments":{"topic_id":"topic-1"}}]}""",
            ),
        )
        var executed = false
        val coordinator = ReplyCoordinator(
            gateway = fakeGateway(outputs),
            localChatToolExecutor = object : LocalChatToolExecutor {
                override suspend fun execute(call: FrontChatToolCall): kotlinx.serialization.json.JsonObject {
                    executed = true
                    return buildJsonObject { put("ok", true) }
                }
            },
        )
        assertTrue(coordinator.generate(context()) is ModelServiceResult.Failure)
        assertTrue(!executed)
    }

    @Test
    fun rejectsWholeAttemptWhenRouteChangesBeforeCommit() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                "{\"task\":\"A_INTAKE\",\"action\":\"ANSWER\"}",
                "{\"replyGoal\":\"回答\",\"mustAddress\":[],\"mayExpress\":[],\"internalOnly\":[]}",
                "纯文本回复",
                "{\"reviewKind\":\"FINAL_REPLY\",\"verdict\":\"PASS\",\"issues\":[],\"evidenceIds\":[]}",
            ),
        )
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult =
                TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )

            override fun isRouteCurrent(snapshot: ModelRouteSnapshot): Boolean = false
        }
        assertTrue(ReplyCoordinator(gateway).generate(context()) is ModelServiceResult.Failure)
    }

    @Test
    fun rejectsDirectDraftWhenRouteChangesAfterExpression() = runBlocking {
        val outputs = ArrayDeque(
            listOf(
                """{"task":"A_INTAKE","purpose":"DIRECT","action":"ANSWER","directResponseBrief":{"replyGoal":"回答","mustAddress":[],"mayExpress":[],"internalOnly":[]}}""",
                "纯文本回复",
            ),
        )
        val gateway = object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult =
                TaskModelResult.Success(
                    content = outputs.removeFirst(),
                    snapshot = TaskModelRouter().resolve(request.task),
                    trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                    provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
                )

            override fun isRouteCurrent(snapshot: ModelRouteSnapshot): Boolean = false
        }
        assertTrue(ReplyCoordinator(gateway).generate(context()) is ModelServiceResult.Failure)
    }

    private fun context() = ContextPack(
        currentContext = CurrentContext(localTime = "2026-09-19T12:00:00+08:00"),
        event = kotlinx.serialization.json.buildJsonObject {
            put("payload", kotlinx.serialization.json.buildJsonObject {
                put("user_message", kotlinx.serialization.json.buildJsonObject {
                    put("id", "CURRENT")
                    put("content", "当前用户原话")
                })
                put("recent_messages", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", "RECENT")
                        put("role", "ASSISTANT")
                        put("content", "近期已经提交的对话")
                    })
                })
            })
        },
        character = CharacterContext(immutableCore = kotlinx.serialization.json.buildJsonObject { }),
    )

    private fun fakeGateway(outputs: ArrayDeque<String>) = object : TaskModelGateway {
        override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult =
            TaskModelResult.Success(
                content = outputs.removeFirst(),
                snapshot = TaskModelRouter().resolve(request.task),
                trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace("fake"),
                provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, "fake"),
            )
    }
}
