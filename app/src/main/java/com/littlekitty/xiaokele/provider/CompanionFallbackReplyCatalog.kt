package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.AppMetadataEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * A small, local catalogue of failure replies written once for the confirmed
 * companion.  It is deliberately separate from a failing user turn: if a
 * provider is unavailable, a fallback must not make another live request.
 */
@Serializable
data class CompanionFallbackReplies(
    val general: String,
    val missingInformation: String,
    val capability: String,
    val privacy: String,
    val unsupportedFact: String,
)

@Serializable
private data class CompanionFallbackReplyCache(
    val identityFingerprint: String,
    val replies: CompanionFallbackReplies,
)

class CompanionFallbackReplyCatalog(
    private val db: AppDatabase,
    private val gateway: TaskModelGateway,
    private val timeProvider: TimeProvider,
) {
    private val generationMutex = Mutex()

    /** Returns only a previously committed catalogue; it never reaches a provider. */
    suspend fun cached(context: ContextPack?): CompanionFallbackReplies? {
        val identity = CompanionPromptIdentity.resolve(context)
        if (!identity.isConfirmed) return null
        val cache = db.metadataDao().get(CACHE_KEY)
            ?.let { raw -> runCatching { JsonCodec.decode<CompanionFallbackReplyCache>(raw) }.getOrNull() }
            ?: return null
        return cache.replies.takeIf { cache.identityFingerprint == identityFingerprint(identity) && it.isValid() }
    }

    /**
     * Creates the catalogue only when it is absent for the current confirmed
     * persona.  Failures intentionally leave no partial value, so a later app
     * start or configuration update can try again.
     */
    suspend fun ensureGenerated(context: ContextPack?): CompanionFallbackReplies? = generationMutex.withLock {
        cached(context)?.let { return@withLock it }
        val identity = CompanionPromptIdentity.resolve(context)
        if (!identity.isConfirmed) return@withLock null

        val result = try {
            gateway.generate(
                TaskModelRequest(
                    task = TaskKind.B2_EXPRESSION,
                    structured = false,
                    systemPrompt = CATALOG_SYSTEM_PROMPT,
                    userPayload = buildString {
                        appendLine(CompanionPromptIdentity.personaSection(identity))
                        appendLine()
                        appendLine("请按上述已确认人格生成这五条离线失败提示。")
                    },
                    scenarioId = "companion_fallback_catalog",
                    redactDiagnostics = true,
                ),
                GenerationBudget(maxRequests = 1),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock null
        }
        val generated = (result as? TaskModelResult.Success)
            ?.takeIf { gateway.isRouteCurrent(it.snapshot) && it.commitGuard() }
            ?.content
            ?.let(::parseReplies)
            ?: return@withLock null
        db.metadataDao().put(
            AppMetadataEntity(
                key = CACHE_KEY,
                value = JsonCodec.encode(CompanionFallbackReplyCache(identityFingerprint(identity), generated)),
                updatedAtEpochMillis = timeProvider.now().toEpochMilli(),
            ),
        )
        generated
    }

    /** Used by setup and app-start warmups, including installations upgraded from older builds. */
    suspend fun ensureForStoredPersona(): CompanionFallbackReplies? {
        val core = db.characterCoreDao().latest()?.contentJson
            ?.let { raw -> runCatching { JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrNull() }
            ?: return null
        return ensureGenerated(ContextPack(currentContext = CurrentContext(""), character = CharacterContext(core)))
    }

    private fun parseReplies(raw: String): CompanionFallbackReplies? {
        val values = raw.lineSequence()
            .map(String::trim)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()
        return CompanionFallbackReplies(
            general = values["GENERAL"].orEmpty(),
            missingInformation = values["MISSING_INFORMATION"].orEmpty(),
            capability = values["CAPABILITY"].orEmpty(),
            privacy = values["PRIVACY"].orEmpty(),
            unsupportedFact = values["UNSUPPORTED_FACT"].orEmpty(),
        ).takeIf { it.isValid() }
    }

    private fun CompanionFallbackReplies.isValid(): Boolean = listOf(
        general, missingInformation, capability, privacy, unsupportedFact,
    ).all { text -> text.isNotBlank() && text.length <= MAX_REPLY_LENGTH && '\n' !in text && '\r' !in text }

    private fun identityFingerprint(identity: CompanionPromptIdentity.Resolved): String {
        val source = listOf(
            identity.companionName,
            identity.personaText.orEmpty(),
            identity.expressionRules.joinToString("\u0000"),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val CACHE_KEY = "companion_fallback/replies_v1"
        private const val MAX_REPLY_LENGTH = 160
        private val CATALOG_SYSTEM_PROMPT = """
            你正在为一个已经确认人格的小狗角色，预先写五条可离线使用的固定失败提示。
            它们不会回答任何具体用户问题，只在系统无法完成正常回复时使用。
            每条只用一两句自然中文，不编造事实、操作结果、共同经历、触碰、未来承诺或追问；不要提及模型、系统、审查、错误码或内部流程。
            必须严格只输出以下五行，等号左边不得修改；每行不换行：
            GENERAL=一般处理失败时的诚实短句
            MISSING_INFORMATION=缺少可靠依据时的诚实短句
            CAPABILITY=没有能力实际完成请求时的诚实短句
            PRIVACY=涉及隐私或权限时的诚实短句
            UNSUPPORTED_FACT=不能把猜测当事实时的诚实短句
        """.trimIndent()
    }
}

/** Best-effort setup work. A missing catalogue is retried on a future warmup. */
class CompanionFallbackReplyWarmup(
    private val catalog: CompanionFallbackReplyCatalog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun schedule() {
        scope.launch { runCatching { catalog.ensureForStoredPersona() } }
    }
}
