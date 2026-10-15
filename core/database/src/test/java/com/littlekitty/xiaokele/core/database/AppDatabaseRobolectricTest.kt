package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseRobolectricTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun metadataDaoCanWriteAndRead() = runBlocking {
        db.metadataDao().put(
            AppMetadataEntity(
                key = "schema.marker",
                value = "phase0",
                updatedAtEpochMillis = 1_755_300_000_000L,
            ),
        )
        assertEquals("phase0", db.metadataDao().get("schema.marker"))
    }

    @Test
    fun missingKeyReturnsNull() = runBlocking {
        assertNull(db.metadataDao().get("missing"))
    }

    @Test
    fun migrationRegistryIsPresentAndNotDestructive() {
        assertEquals(AppDatabase.VERSION - 1, AppMigrations.all().size)
    }
}
