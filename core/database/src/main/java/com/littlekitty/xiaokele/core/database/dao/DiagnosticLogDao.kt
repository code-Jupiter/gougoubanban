package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogEntity

@Dao
interface DiagnosticLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY recorded_at ASC, id ASC")
    suspend fun allAscending(): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_logs WHERE category = :category AND name = :name ORDER BY recorded_at DESC")
    suspend fun forTask(category: String, name: String): List<DiagnosticLogEntity>

    @Query("SELECT COUNT(*) FROM diagnostic_logs")
    suspend fun count(): Int
}
