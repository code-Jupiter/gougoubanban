package com.littlekitty.xiaokele.animation

import com.littlekitty.xiaokele.core.database.state.PetStateIds

/**
 * Human-facing local commands for the animations already bundled in the app.
 * These commands intentionally stay outside the model pipeline so a slash
 * command is deterministic and never consumes a network/model request.
 */
object AnimationCommandCatalog {

    data class Entry(
        val command: String,
        val label: String,
        val description: String,
        val animationId: String,
        val stateId: String,
        val aliases: Set<String> = emptySet(),
    )

    sealed interface ParseResult {
        data class Action(val entry: Entry) : ParseResult

        data object Help : ParseResult

        data class Unknown(val command: String) : ParseResult
    }

    val entries: List<Entry> = listOf(
        Entry(
            command = "/待机",
            label = "待机",
            description = "自然待机、眨眼和安静待在原地的样子",
            animationId = AnimationCatalog.ID_DEFAULT_IDLE,
            stateId = PetStateIds.IDLE,
            aliases = setOf("/默认", "/idle", "/default_idle"),
        ),
        Entry(
            command = "/发呆",
            label = "发呆",
            description = "歪头观察、像在发呆一样等待",
            animationId = AnimationCatalog.ID_WAITING,
            stateId = PetStateIds.WAITING,
            aliases = setOf("/等待", "/waiting", "/dog_waiting"),
        ),
        Entry(
            command = "/睡觉",
            label = "睡觉",
            description = "坐下打盹，然后恢复站立",
            animationId = AnimationCatalog.ID_SLEEPING,
            stateId = PetStateIds.SLEEPING,
            aliases = setOf("/睡眠", "/sleep", "/dog_sleeping"),
        ),
        Entry(
            command = "/委屈",
            label = "委屈",
            description = "低头抱膝蜷缩，再慢慢恢复",
            animationId = AnimationCatalog.ID_CURLED_UP,
            stateId = PetStateIds.CURLED_UP,
            aliases = setOf("/蜷缩", "/curled_up", "/dog_curled_up"),
        ),
        Entry(
            command = "/期待",
            label = "期待",
            description = "克制地轻轻向上蹦两下",
            animationId = AnimationCatalog.ID_EXPECTING,
            stateId = PetStateIds.EXPECTING,
            aliases = setOf("/expecting", "/dog_expecting"),
        ),
        Entry(
            command = "/安慰",
            label = "安慰",
            description = "向前靠近，做出安慰和关心的动作",
            animationId = AnimationCatalog.ID_CARING,
            stateId = PetStateIds.CARING,
            aliases = setOf("/关心", "/caring", "/dog_caring"),
        ),
        Entry(
            command = "/守护",
            label = "守护",
            description = "左右观察，做出守护和警戒动作",
            animationId = AnimationCatalog.ID_WORK_GUARD,
            stateId = PetStateIds.WORK_GUARD,
            aliases = setOf("/工作守护", "/work_guard", "/dog_work_guard"),
        ),
        Entry(
            command = "/欢迎回来",
            label = "欢迎回来",
            description = "蹲下、跳起欢迎，再恢复平静",
            animationId = AnimationCatalog.ID_WELCOME_BACK,
            stateId = PetStateIds.WELCOME_BACK,
            aliases = setOf("/欢迎", "/welcome_back", "/dog_welcome_back"),
        ),
    )

    private val lookup: Map<String, Entry> = entries
        .flatMap { entry -> listOf(entry.command to entry) + entry.aliases.map { it to entry } }
        .associate { (command, entry) -> normalize(command) to entry }

    fun parse(rawText: String): ParseResult? {
        val command = rawText.trim()
        if (!command.startsWith("/")) return null

        val normalized = normalize(command)
        if (normalized == "/帮助" || normalized == "/动作" || normalized == "/help") {
            return ParseResult.Help
        }
        return lookup[normalized]?.let { ParseResult.Action(it) }
            ?: ParseResult.Unknown(command)
    }

    fun helpText(): String = buildString {
        append("可调用动作：")
        entries.forEach { entry ->
            append('\n')
            append(entry.command)
            append("：")
            append(entry.description)
        }
    }

    private fun normalize(command: String): String = command.trim().lowercase()
}
