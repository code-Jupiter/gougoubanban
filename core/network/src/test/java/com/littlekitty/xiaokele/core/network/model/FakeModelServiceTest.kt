package com.littlekitty.xiaokele.core.network.model

import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeModelServiceTest {

    private val context = ContextPack(
        currentContext = CurrentContext(localTime = "2026-08-16T10:00:00+08:00"),
        character = CharacterContext(immutableCore = JsonObject(emptyMap())),
    )

    @Test
    fun successModeReturnsDecodableDecision() = runBlocking {
        val result = FakeModelService(FakeModelMode.SUCCESS).generate(context)
        assertTrue(result is ModelServiceResult.Success)
        val decision = JsonCodec.decode<AgentDecision>((result as ModelServiceResult.Success).rawDecisionJson)
        assertEquals("CREATE_PLAN", decision.decision)
        assertEquals(2, decision.outputs.size)
    }

    @Test
    fun failureModeReturnsSafeModelFailure() = runBlocking {
        val result = FakeModelService(FakeModelMode.FAILURE).generate(context)
        assertTrue(result is ModelServiceResult.Failure)
        assertEquals(ErrorCode.MODEL_FAILURE, (result as ModelServiceResult.Failure).error.code)
    }

    @Test
    fun invalidJsonModeReturnsMalformedRawText() = runBlocking {
        val result = FakeModelService(FakeModelMode.INVALID_JSON).generate(context)
        assertTrue(result is ModelServiceResult.Success)
        assertTrue((result as ModelServiceResult.Success).rawDecisionJson.startsWith("{"))
    }
}
