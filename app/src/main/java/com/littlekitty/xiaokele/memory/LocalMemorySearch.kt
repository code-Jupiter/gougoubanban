package com.littlekitty.xiaokele.memory

import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.memory.MatchKind
import com.littlekitty.xiaokele.core.database.memory.MemoryHit
import com.littlekitty.xiaokele.core.database.memory.MemoryQuery
import com.littlekitty.xiaokele.core.database.memory.MemorySearch
import com.littlekitty.xiaokele.core.database.memory.VectorBlobCodec
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingResult
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingService

/**
 * Local Room-backed MemorySearch (architecture section 28).
 *
 * Step 1 is always deterministic metadata filtering (active, valid, type,
 * source). When a remote embedding service is configured and returns a query
 * vector, cosine similarity is computed in Kotlin against local vector BLOBs.
 * If the embedding service is unconfigured/fails, the same method degrades to
 * lexical relevance and never throws (FR-SERVICE-10, FR-MEM-05/09).
 */
class LocalMemorySearch(
    private val db: AppDatabase,
    private val embeddingService: EmbeddingService,
    private val timeProvider: TimeProvider,
) : MemorySearch {

    override suspend fun search(query: MemoryQuery): List<MemoryHit> {
        val now = timeProvider.now().toEpochMilli()
        val types = query.types
        val existingMessageIds = db.messageDao().allAscending().asSequence().map { it.id }.toSet()
        val candidates = db.memoryDao().activeAt(now).filter { memory ->
            (memory.sourceMessageId == null || memory.sourceMessageId in existingMessageIds) &&
            (types == null || memory.type in types) &&
                (query.sourceMessageId == null || memory.sourceMessageId == query.sourceMessageId) &&
                (query.sourceEventId == null || memory.sourceEventId == query.sourceEventId)
        }
        if (candidates.isEmpty()) return emptyList()

        val queryText = query.text?.trim().orEmpty()
        if (queryText.isBlank()) {
            return candidates
                .map { MemoryHit(it, metadataScore(it), MatchKind.LEXICAL) }
                .sortedByDescending { it.score }
                .take(query.limit)
        }

        val lexicalHits = candidates
            .map { memory ->
                val lexical = lexicalRelevance(queryText, memory)
                memory to lexical
            }
            .filter { it.second > 0.0 }

        val embedding = try { embeddingService.embedQuery(listOf(queryText)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (embedding !is EmbeddingResult.Success) {
            return lexicalHits
                .map { (memory, lexical) ->
                    MemoryHit(memory, combine(metadataScore(memory), lexical, 0.5), MatchKind.LEXICAL)
                }
                .sortedByDescending { it.score }
                .take(query.limit)
        }

        val batch = embedding.batch
        val queryVector = batch.vectors.firstOrNull() ?: return lexicalOnly(lexicalHits, query.limit)
        if (queryVector.values.size != batch.dimension) return lexicalOnly(lexicalHits, query.limit)

        val embeddingsByMemory = db.memoryEmbeddingDao()
            .forMemoryIds(candidates.map { it.id })
            .filter { it.modelId == batch.modelId && it.dimension == batch.dimension }
            .groupBy { it.memoryId }

        val vectorHits = mutableListOf<Pair<MemoryEntity, Double>>()
        candidates.forEach { memory ->
            val embeddingEntity = embeddingsByMemory[memory.id]?.firstOrNull()
                ?: return@forEach
            val storedVector = runCatching { VectorBlobCodec.decode(embeddingEntity.vectorBlob) }.getOrNull()
                ?: return@forEach
            if (storedVector.size != batch.dimension) return@forEach
            val cosine = VectorBlobCodec.cosine(queryVector.values, storedVector)
            if (cosine > MIN_VECTOR_RELEVANCE) {
                val vectorScore = (cosine + 1.0) / 2.0
                vectorHits += memory to combine(metadataScore(memory), vectorScore, VECTOR_WEIGHT)
            }
        }

        val hits = vectorHits
            .map { (memory, score) -> MemoryHit(memory, score, MatchKind.VECTOR) }
            .toMutableList()
        if (hits.isEmpty()) {
            return lexicalOnly(lexicalHits, query.limit)
        }
        return hits
            .sortedByDescending { it.score }
            .take(query.limit)
    }

    private fun lexicalOnly(
        lexicalHits: List<Pair<MemoryEntity, Double>>,
        limit: Int,
    ): List<MemoryHit> = lexicalHits
        .map { (memory, lexical) ->
            MemoryHit(memory, combine(metadataScore(memory), lexical, 0.5), MatchKind.LEXICAL)
        }
        .sortedByDescending { it.score }
        .take(limit)

    private fun metadataScore(memory: MemoryEntity): Double =
        (memory.importance * 0.6) + (memory.confidence * 0.4)

    private fun combine(metadata: Double, relevance: Double, relevanceWeight: Double): Double =
        (metadata * (1.0 - relevanceWeight)) + (relevance.coerceIn(0.0, 1.0) * relevanceWeight)

    private fun lexicalRelevance(query: String, memory: MemoryEntity): Double {
        val haystack = listOfNotNull(memory.subject, memory.content, memory.summary)
            .joinToString(" ") { it.trim() }
        if (haystack.isBlank()) return 0.0
        val queryGrams = bigrams(query)
        val haystackGrams = bigrams(haystack)
        if (queryGrams.isEmpty()) return 0.0
        val overlap = queryGrams.intersect(haystackGrams).size
        return overlap.toDouble() / queryGrams.size
    }

    private fun bigrams(raw: String): Set<String> =
        raw.trim().lowercase().replace(Regex("\\s+"), "")
            .windowed(size = 2, step = 1, partialWindows = false)
            .toSet()

    companion object {
        const val MIN_VECTOR_RELEVANCE = 0.0
        const val VECTOR_WEIGHT = 0.65
    }
}
