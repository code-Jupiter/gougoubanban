package com.littlekitty.xiaokele.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteConfigurationBarrierTest {
    @Test
    fun nestedWriteAndReadAreReentrantForAtomicSettingsImport() {
        var completed = false
        RouteConfigurationBarrier.withWrite {
            RouteConfigurationBarrier.withRead {
                RouteConfigurationBarrier.withWrite { completed = true }
            }
        }
        assertTrue(completed)
    }

    @Test
    fun routeWriteWaitsForFinalReadCommitButNotNetworkWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = async {
            RouteConfigurationBarrier.withReadSuspend {
                entered.complete(Unit)
                release.await()
                "room-commit"
            }
        }
        entered.await()

        var writeCompleted = false
        val write = async(Dispatchers.Default) {
            RouteConfigurationBarrier.withWrite { writeCompleted = true }
        }
        kotlinx.coroutines.yield()
        assertFalse(writeCompleted)

        release.complete(Unit)
        assertTrue(withTimeout(1_000) { read.await() == "room-commit" })
        write.await()
        assertTrue(writeCompleted)
    }
}
