package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity

@Dao
interface CharacterCoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterCoreEntity)

    @Query("SELECT * FROM character_core ORDER BY version DESC LIMIT 1")
    suspend fun latest(): CharacterCoreEntity?

    @Query("SELECT * FROM character_core ORDER BY version ASC, id ASC")
    suspend fun all(): List<CharacterCoreEntity>

    @Query("SELECT COUNT(*) FROM character_core")
    suspend fun count(): Int
}

@Dao
interface BehaviorTendencyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BehaviorTendencyEntity)

    @Query("SELECT * FROM behavior_tendencies WHERE id = :id")
    suspend fun byId(id: String): BehaviorTendencyEntity?

    @Query("SELECT * FROM behavior_tendencies WHERE `key` = :key ORDER BY updated_at DESC")
    suspend fun forKey(key: String): List<BehaviorTendencyEntity>

    @Query("SELECT * FROM behavior_tendencies ORDER BY updated_at DESC")
    suspend fun all(): List<BehaviorTendencyEntity>

    @Query(
        """
        UPDATE behavior_tendencies
        SET status = :newStatus,
            suppression_level = :suppressionLevel,
            legacy_expression_rule_json = :legacyExpressionRuleJson,
            updated_at = :now
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun transitionToLegacyOrArchived(
        id: String,
        expectedStatus: String,
        newStatus: String,
        suppressionLevel: Double?,
        legacyExpressionRuleJson: String?,
        now: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM behavior_tendencies")
    suspend fun count(): Int
}

@Dao
interface PetStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PetStateEntity)

    @Query("SELECT * FROM pet_state WHERE singleton_id = :singletonId")
    suspend fun get(singletonId: Int = PetStateEntity.SINGLETON_ID): PetStateEntity?

    @Query("SELECT * FROM pet_state WHERE singleton_id = :singletonId")
    fun observeCurrent(singletonId: Int = PetStateEntity.SINGLETON_ID): kotlinx.coroutines.flow.Flow<PetStateEntity?>

    @Query(
        """
        UPDATE pet_state
        SET state_id = 'IDLE', reason_event_id = NULL, animation_id = NULL, short_text = NULL,
            starts_at = :now, expires_at = NULL, click_target_json = NULL, updated_at = :now
        WHERE singleton_id = :singletonId AND expires_at IS NOT NULL AND expires_at <= :now
        """,
    )
    suspend fun resetExpired(singletonId: Int, now: Long): Int

    @Query(
        """
        UPDATE pet_state
        SET state_id = :contextualDefaultState, reason_event_id = NULL, animation_id = NULL, short_text = NULL,
            starts_at = :now, expires_at = NULL, click_target_json = NULL, updated_at = :now
        WHERE singleton_id = :singletonId AND expires_at IS NOT NULL AND expires_at <= :now
        """,
    )
    suspend fun resetExpiredTo(singletonId: Int, contextualDefaultState: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM pet_state")
    suspend fun count(): Int
}
