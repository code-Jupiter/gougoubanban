package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.*

@Dao
interface CreativeCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: CreativeCandidateEntity)
    @Query("SELECT * FROM creative_candidates WHERE id = :id") suspend fun byId(id: String): CreativeCandidateEntity?
    @Query("SELECT * FROM creative_candidates WHERE status = 'AVAILABLE' ORDER BY novelty_score DESC, created_at DESC LIMIT :limit")
    suspend fun available(limit: Int): List<CreativeCandidateEntity>
    @Query("UPDATE creative_candidates SET status = :status, updated_at = :now WHERE id = :id") suspend fun updateStatus(id: String, status: String, now: Long): Int
}

@Dao
interface CreativeWorkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: CreativeWorkEntity)
    @Query("SELECT * FROM creative_works WHERE id = :id") suspend fun byId(id: String): CreativeWorkEntity?
    @Query("SELECT * FROM creative_works WHERE status = 'ACTIVE' ORDER BY period_end DESC, created_at DESC LIMIT :limit") suspend fun recent(limit: Int): List<CreativeWorkEntity>
    @Query("SELECT * FROM creative_works WHERE work_type = :workType AND period_start = :periodStart AND period_end = :periodEnd AND timezone = :timezone LIMIT 1")
    suspend fun byPeriod(workType: String, periodStart: Long, periodEnd: Long, timezone: String): CreativeWorkEntity?
    @Query("SELECT * FROM creative_works WHERE input_fingerprint = :fingerprint LIMIT 1") suspend fun byFingerprint(fingerprint: String): CreativeWorkEntity?
    @Query("UPDATE creative_works SET status = :status, invalidated_at = :now, updated_at = :now WHERE id = :id") suspend fun invalidate(id: String, status: String, now: Long): Int
}

@Dao
interface CreativeSourceLinkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(entities: List<CreativeSourceLinkEntity>)
    @Query("SELECT * FROM creative_source_links WHERE work_id = :workId ORDER BY source_timestamp ASC, id ASC") suspend fun forWork(workId: String): List<CreativeSourceLinkEntity>
    @Query("SELECT * FROM creative_source_links WHERE source_type = :sourceType AND source_id = :sourceId") suspend fun forSource(sourceType: String, sourceId: String): List<CreativeSourceLinkEntity>
}

@Dao
interface CreativeTopicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: CreativeTopicEntity)
    @Query("SELECT * FROM creative_topics WHERE id = :id") suspend fun byId(id: String): CreativeTopicEntity?
    @Query("SELECT * FROM creative_topics WHERE status = 'AVAILABLE' AND (available_after IS NULL OR available_after <= :now) AND (expires_at IS NULL OR expires_at > :now) ORDER BY priority DESC, novelty DESC, created_at DESC LIMIT :limit")
    suspend fun available(now: Long, limit: Int): List<CreativeTopicEntity>
    @Query("UPDATE creative_topics SET status = :status, updated_at = :now WHERE id = :id") suspend fun updateStatus(id: String, status: String, now: Long): Int
    @Query("UPDATE creative_topics SET last_offered_at = :now, offer_count = offer_count + 1, status = 'RESERVED', updated_at = :now WHERE id = :id AND status = 'AVAILABLE'") suspend fun reserve(id: String, now: Long): Int
    @Query("UPDATE creative_topics SET status = :status, updated_at = :now WHERE id = :id") suspend fun release(id: String, status: String, now: Long): Int
}

@Dao
interface CreativeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: CreativeSessionEntity)
    @Query("SELECT * FROM creative_sessions WHERE id = :id") suspend fun byId(id: String): CreativeSessionEntity?
    @Query("SELECT * FROM creative_sessions WHERE status IN ('AWAITING_CONSENT', 'ACTIVE', 'PAUSED') ORDER BY updated_at DESC LIMIT 1") suspend fun active(): CreativeSessionEntity?
}

@Dao
interface CreativePreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: CreativePreferenceEntity)
    @Query("SELECT * FROM creative_preferences WHERE feature_key = :key") suspend fun byKey(key: String): CreativePreferenceEntity?
}

@Dao
interface CreativeOfferEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entity: CreativeOfferEventEntity): Long
    @Query("SELECT * FROM creative_offer_events WHERE id = :id") suspend fun byId(id: String): CreativeOfferEventEntity?
    @Query("SELECT * FROM creative_offer_events WHERE bucket = :bucket AND dedup_key = :dedupKey LIMIT 1") suspend fun byDedupKey(bucket: String, dedupKey: String): CreativeOfferEventEntity?
    @Query("SELECT COUNT(*) FROM creative_offer_events WHERE bucket = :bucket AND is_user_initiated = 0 AND status = 'SENT' AND user_dialogue_turn_id IN (:turnIds)") suspend fun countSentForTurns(bucket: String, turnIds: List<String>): Int
    @Query("SELECT COUNT(*) FROM creative_offer_events WHERE bucket = :bucket AND is_user_initiated = 0 AND status = 'SENT' AND sent_at >= :after") suspend fun countSentSince(bucket: String, after: Long): Int
    @Query("SELECT * FROM creative_offer_events WHERE bucket = :bucket AND offer_type = 'BEDTIME_CHECK' AND status = 'CHECKED' AND created_at >= :start AND created_at < :end LIMIT 1") suspend fun bedtimeCheckBetween(bucket: String, start: Long, end: Long): CreativeOfferEventEntity?
    @Query("SELECT * FROM creative_offer_events WHERE bucket = :bucket AND is_user_initiated = 0 AND status = 'SENT' AND sent_at >= :after ORDER BY sent_at DESC LIMIT 1") suspend fun latestSentSince(bucket: String, after: Long): CreativeOfferEventEntity?
    @Query("UPDATE creative_offer_events SET status = 'SENT', sent_at = :now, message_id = :messageId WHERE id = :id AND status = 'RESERVED'") suspend fun markSent(id: String, messageId: String, now: Long): Int
    @Query("UPDATE creative_offer_events SET status = 'RELEASED' WHERE id = :id AND status = 'RESERVED'") suspend fun release(id: String): Int
}

@Dao
interface UserDialogueTurnDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entity: UserDialogueTurnEntity)
    @Query("SELECT * FROM user_dialogue_turns WHERE last_message_id = :messageId OR first_message_id = :messageId LIMIT 1") suspend fun byMessageId(messageId: String): UserDialogueTurnEntity?
    @Query("SELECT * FROM user_dialogue_turns ORDER BY last_user_message_at DESC, id DESC LIMIT :limit") suspend fun recent(limit: Int): List<UserDialogueTurnEntity>
    @Query("SELECT * FROM user_dialogue_turns WHERE last_user_message_at >= :start AND last_user_message_at < :end ORDER BY last_user_message_at ASC") suspend fun between(start: Long, end: Long): List<UserDialogueTurnEntity>
}

@Dao
interface CreativeTaskRunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entity: CreativeTaskRunEntity): Long
    @Query("SELECT * FROM creative_task_runs WHERE task_kind = :taskKind AND period_key = :periodKey AND timezone = :timezone LIMIT 1") suspend fun byKey(taskKind: String, periodKey: String, timezone: String): CreativeTaskRunEntity?
    @Query("SELECT * FROM creative_task_runs WHERE status IN ('PENDING', 'RETRYABLE', 'RUNNING') ORDER BY due_at ASC, id ASC LIMIT :limit") suspend fun pending(limit: Int): List<CreativeTaskRunEntity>
    @Query("UPDATE creative_task_runs SET status = :status, started_at = :startedAt, attempt_count = attempt_count + 1, updated_at = :now WHERE id = :id AND status IN ('PENDING', 'RETRYABLE')") suspend fun claim(id: String, status: String, startedAt: Long, now: Long): Int
    @Query("UPDATE creative_task_runs SET status = :status, completed_at = :completedAt, result_work_id = :resultWorkId, source_watermark = :sourceWatermark, input_fingerprint = :inputFingerprint, terminal_reason = :reason, updated_at = :now WHERE id = :id") suspend fun finish(id: String, status: String, completedAt: Long, resultWorkId: String?, sourceWatermark: Long?, inputFingerprint: String?, reason: String?, now: Long): Int
    @Query("UPDATE creative_task_runs SET status = 'RETRYABLE', terminal_reason = :reason, updated_at = :now WHERE id = :id") suspend fun retryable(id: String, reason: String, now: Long): Int
    @Query("UPDATE creative_task_runs SET status = 'RETRYABLE', started_at = NULL, terminal_reason = :reason, updated_at = :now WHERE status = 'RUNNING' AND started_at IS NOT NULL AND started_at < :startedBefore")
    suspend fun requeueStaleRunning(startedBefore: Long, reason: String, now: Long): Int
    @Query("SELECT * FROM creative_task_runs ORDER BY due_at DESC, id DESC LIMIT :limit") suspend fun recent(limit: Int): List<CreativeTaskRunEntity>
}
