package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND (:type IS NULL OR type = :type) ORDER BY importance DESC")
    suspend fun active(type: String?): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memories
        WHERE status = 'ACTIVE'
          AND (valid_from IS NULL OR valid_from <= :now)
          AND (valid_until IS NULL OR valid_until > :now)
        ORDER BY importance DESC, confidence DESC, updated_at DESC
        """,
    )
    suspend fun activeAt(now: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updated_at DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("UPDATE memories SET status = :newStatus, updated_at = :now WHERE id = :id AND status = :expectedStatus")
    suspend fun transition(id: String, expectedStatus: String, newStatus: String, now: Long): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE memories SET status = 'EXPIRED', updated_at = :now WHERE valid_until IS NOT NULL AND valid_until <= :now AND status = 'ACTIVE'")
    suspend fun expireDue(now: Long): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}

@Dao
interface MemoryEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings WHERE memory_id = :memoryId AND model_id = :modelId")
    suspend fun byMemoryAndModel(memoryId: String, modelId: String): MemoryEmbeddingEntity?

    @Query("SELECT * FROM memory_embeddings WHERE memory_id = :memoryId ORDER BY created_at DESC")
    suspend fun forMemory(memoryId: String): List<MemoryEmbeddingEntity>

    @Query("SELECT * FROM memory_embeddings WHERE memory_id IN (:memoryIds) ORDER BY created_at DESC")
    suspend fun forMemoryIds(memoryIds: List<String>): List<MemoryEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun count(): Int

    @Query("DELETE FROM memory_embeddings WHERE memory_id = :memoryId")
    suspend fun deleteForMemory(memoryId: String): Int
}

@Dao
interface UserTraitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserTraitEntity)

    @Query("SELECT * FROM user_traits WHERE id = :id")
    suspend fun byId(id: String): UserTraitEntity?

    @Query("SELECT * FROM user_traits WHERE `key` = :key")
    suspend fun byKey(key: String): UserTraitEntity?

    @Query("SELECT * FROM user_traits ORDER BY updated_at DESC")
    suspend fun all(): List<UserTraitEntity>

    @Query("SELECT COUNT(*) FROM user_traits")
    suspend fun count(): Int
}
