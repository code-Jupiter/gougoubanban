package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.memory.MemoryAnalysisHook
import com.littlekitty.xiaokele.memory.MemoryAnalysisRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Blocks memory analysis until the user has confirmed their companion.
 *
 * Before confirmation there is no locked persona, so any memory, trait or
 * behaviour patch produced here would be attributed to a character the user
 * never approved. The request is dropped rather than queued: the messages that
 * would have triggered it are chat messages, and chat itself is already
 * blocked by [FirstCreateGate].
 *
 * The delegate is supplied as a factory, not as an instance, because
 * `MemoryAnalysisProcessor` itself depends on this hook; resolving it eagerly
 * here would create a dependency cycle.
 */
class FirstCreateGatedMemoryAnalysisHook(
    private val delegateFactory: () -> MemoryAnalysisHook,
    private val gate: FirstCreateGate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MemoryAnalysisHook {

    override fun enqueue(request: MemoryAnalysisRequest) {
        scope.launch {
            if (gate.isCompleted()) {
                delegateFactory().enqueue(request)
            }
            // Otherwise the analysis is intentionally skipped; no partial
            // memory state is written for an unconfirmed companion.
        }
    }
}
