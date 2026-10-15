package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase6ExternalCacheTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var guard: ExternalCacheEmissionGuard

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guard = ExternalCacheEmissionGuard(db.externalCacheDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun guardKeepsFresherCacheWhenStaleCandidateArrives() = runBlocking {
        val fresh = guard.emitIfFresher("k", "s", """{"v":2}""", 2000, 3000)
        val stale = guard.emitIfFresher("k", "s", """{"v":1}""", 1000, 3000)

        assertTrue(fresh.written)
        assertFalse(stale.written)
        assertTrue(stale.skippedStale)
        assertEquals("""{"v":2}""", db.externalCacheDao().byKeyAndSource("k", "s")?.payloadJson)
    }

    @Test
    fun twentyConcurrentStaleWritesNeverOverwriteFreshCache() = runBlocking {
        guard.emitIfFresher("concurrent", "s", """{"v":"fresh"}""", 1000, 9000)
        val results = coroutineScope {
            (1..20).map {
                async(Dispatchers.IO) {
                    guard.emitIfFresher("concurrent", "s", """{"v":"stale-$it"}""", 500, 9000)
                }
            }.awaitAll()
        }
        assertEquals(0, results.count { it.written })
        assertEquals(20, results.count { it.skippedStale })
        assertEquals("""{"v":"fresh"}""", db.externalCacheDao().byKeyAndSource("concurrent", "s")?.payloadJson)
    }
}
