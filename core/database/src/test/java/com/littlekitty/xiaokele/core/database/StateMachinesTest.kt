package com.littlekitty.xiaokele.core.database

import com.littlekitty.xiaokele.core.database.state.ActionExecutionStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionPlanStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.MemoryStateMachine
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.PetStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStateMachine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachinesTest {

    @Test
    fun todoClaimIsOnlyAllowedFromWaitingOrReady() {
        assertTrue(TodoStateMachine.canTransition(TodoStatuses.WAITING, TodoStatuses.CLAIMED))
        assertTrue(TodoStateMachine.canTransition(TodoStatuses.READY, TodoStatuses.CLAIMED))
        assertFalse(TodoStateMachine.canTransition(TodoStatuses.CANDIDATE, TodoStatuses.CLAIMED))
        assertFalse(TodoStateMachine.canTransition(TodoStatuses.EXECUTED, TodoStatuses.CLAIMED))
    }

    @Test
    fun todoStaleClaimRecoveryReturnsToReadyButTerminalStaysTerminal() {
        assertTrue(TodoStateMachine.canTransition(TodoStatuses.CLAIMED, TodoStatuses.READY))
        assertTrue(TodoStateMachine.canTransition(TodoStatuses.CLAIMED, TodoStatuses.EXPIRED))
        assertFalse(TodoStateMachine.canTransition(TodoStatuses.EXECUTED, TodoStatuses.READY))
        assertFalse(TodoStateMachine.canTransition(TodoStatuses.EXPIRED, TodoStatuses.READY))
    }

    @Test
    fun eventMachineRecoversStaleProcessingToReceivedOnly() {
        assertTrue(EventStateMachine.canTransition("RECEIVED", "PROCESSING"))
        assertTrue(EventStateMachine.canTransition("PROCESSING", "RECEIVED"))
        assertFalse(EventStateMachine.canTransition("COMPLETED", "RECEIVED"))
    }

    @Test
    fun petStateMachineKeepsDefinedStatesAndRejectsUnknown() {
        assertTrue(PetStateMachine.canTransition(PetStateIds.IDLE, PetStateIds.WAITING))
        assertTrue(PetStateMachine.canTransition(PetStateIds.CARING, PetStateIds.WORK_GUARD))
        assertFalse(PetStateMachine.canTransition(PetStateIds.SLEEPING, PetStateIds.CARING))
        assertFalse(PetStateIds.all.contains("UNKNOWN"))
    }

    @Test
    fun actionPlanAndExecutionMachinesFollowLinearFlow() {
        assertTrue(ActionPlanStateMachine.canTransition("PENDING", "VALIDATED"))
        assertTrue(ActionPlanStateMachine.canTransition("VALIDATED", "RUNNING"))
        assertTrue(ActionPlanStateMachine.canTransition("RUNNING", "COMPLETED"))
        assertFalse(ActionPlanStateMachine.canTransition("COMPLETED", "RUNNING"))
        assertTrue(ActionExecutionStateMachine.canTransition("PENDING", "RUNNING"))
        assertFalse(ActionExecutionStateMachine.canTransition("COMPLETED", "PENDING"))
    }

    @Test
    fun memoryAndTriggerMachinesRejectBackwardTerminalTransitions() {
        assertTrue(MemoryStateMachine.canTransition("ACTIVE", "SUPERSEDED"))
        assertFalse(MemoryStateMachine.canTransition("SUPERSEDED", "ACTIVE"))
        assertTrue(TriggerStateMachine.canTransition("ACTIVE", "FIRED"))
        assertTrue(TriggerStateMachine.canTransition("FIRED", "ACTIVE"))
        assertFalse(TriggerStateMachine.canTransition("COMPLETED", "ACTIVE"))
    }
}
