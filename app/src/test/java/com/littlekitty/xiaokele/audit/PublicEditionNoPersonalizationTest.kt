package com.littlekitty.xiaokele.audit

import com.littlekitty.xiaokele.provider.CompanionPromptIdentity
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Public-edition gate: no personalised configuration ships in the build.
 *
 * "Personalisation" here means anything that hardcodes a specific companion
 * identity, a specific user nickname, a private appearance/relationship
 * setting, or an adult/intimate default. Those must come from the user's own
 * first-create input instead, so this gate is deliberately a source gate.
 */
class PublicEditionNoPersonalizationTest {

    private val moduleRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not locate project root from ${File("").absolutePath}")
    }

    private val scannedRoots: List<File> by lazy {
        listOf(
            File(moduleRoot, "app/src/main"),
            File(moduleRoot, "app/src/debug"),
            File(moduleRoot, "core/common/src/main"),
            File(moduleRoot, "core/database/src/main"),
            File(moduleRoot, "core/network/src/main"),
        ).filter { it.isDirectory }
    }

    @Test
    fun noPersonalisedCompanionOrUserNamesRemainInShippingSource() {
        val hits = mutableListOf<String>()
        scannedRoots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in SCANNED_EXTENSIONS }.toList() }
            .forEach { file ->
                val relative = file.relativeTo(moduleRoot).path
                file.readLines().forEachIndexed { index, line ->
                    if (isCommentLine(line)) return@forEachIndexed
                    FORBIDDEN_IDENTITY_TOKENS.forEach { token ->
                        if (line.contains(token)) hits += "$relative:${index + 1}: $token"
                    }
                }
            }

        assertTrue(
            "personalised identity must not appear in the public edition:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun noAdultOrIntimateDefaultsRemainInShippingSource() {
        val hits = mutableListOf<String>()
        scannedRoots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in SCANNED_EXTENSIONS }.toList() }
            .forEach { file ->
                val relative = file.relativeTo(moduleRoot).path
                if (isSafetyPatternSource(relative)) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    if (isCommentLine(line)) return@forEachIndexed
                    // A prohibition ("不要生成色情或露骨性内容") is allowed; an
                    // instruction to produce such content is not.
                    if (isProhibitionLine(line)) return@forEachIndexed
                    FORBIDDEN_ADULT_TOKENS.forEach { token ->
                        if (line.contains(token)) hits += "$relative:${index + 1}: $token"
                    }
                }
            }

        assertTrue(
            "adult/intimate defaults must not appear in the public edition:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun noFixedAppearanceRemainsInShippingSource() {
        // The public edition derives appearance from the user's own first-create
        // input. A hardcoded body description in shipping source becomes a model
        // prompt anchor and makes the companion describe a body the user never
        // confirmed (observed live: a reply about tying up "耳朵和头发").
        val hits = mutableListOf<String>()
        scannedRoots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in SCANNED_EXTENSIONS }.toList() }
            .forEach { file ->
                val relative = file.relativeTo(moduleRoot).path
                if (isSafetyPatternSource(relative)) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    if (isCommentLine(line)) return@forEachIndexed
                    if (isProhibitionLine(line)) return@forEachIndexed
                    FORBIDDEN_APPEARANCE_TOKENS.forEach { token ->
                        if (line.contains(token)) hits += "$relative:${index + 1}: $token"
                    }
                }
            }

        assertTrue(
            "fixed appearance must not appear in the public edition:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun noRelationshipIsImposedByShippingSource() {
        // A generic product must not decide how the companion relates to the
        // user. The only permitted writer is the first-create template, and it
        // must take the value from the user's own input.
        val writers = mutableListOf<String>()
        scannedRoots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in SCANNED_EXTENSIONS }.toList() }
            .forEach { file ->
                val relative = file.relativeTo(moduleRoot).path
                file.readLines().forEachIndexed { index, line ->
                    if (isCommentLine(line)) return@forEachIndexed
                    if (line.contains("put(\"relationship\"")) {
                        writers += "$relative:${index + 1}: ${line.trim().take(90)}"
                    }
                }
            }

        val offenders = writers.filterNot { it.contains("firstcreate/NeutralPersonaTemplate.kt") }
        assertTrue(
            "only the first-create template may write a relationship:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // And that single writer must use the user's input, not a constant.
        assertTrue(
            "the relationship must come from user input:\n" + writers.joinToString("\n"),
            writers.any { it.contains("draft.relationship") },
        )
    }

    @Test
    fun resolvedIdentityComesFromTheConfirmedContextNotAFixedName() {
        // Without a character core the resolver must fall back, never invent a
        // product-specific name.
        val empty = CompanionPromptIdentity.resolve(null)
        assertEquals(CompanionPromptIdentity.FALLBACK_COMPANION_NAME, empty.companionName)
        assertEquals(CompanionPromptIdentity.FALLBACK_USER_ADDRESS, empty.userAddress)

        val context = com.littlekitty.xiaokele.core.common.protocol.ContextPack(
            currentContext = com.littlekitty.xiaokele.core.common.protocol.CurrentContext(localTime = "2026-01-01T00:00:00Z"),
            character = com.littlekitty.xiaokele.core.common.protocol.CharacterContext(
                immutableCore = buildJsonObject {
                    put("display_name", "豆豆")
                    put("user_name", "小雨")
                },
            ),
        )
        val resolved = CompanionPromptIdentity.resolve(context)
        assertEquals("豆豆", resolved.companionName)
        assertEquals("小雨", resolved.userAddress)

        val rules = CompanionPromptIdentity.identityRules(resolved)
        // The rules carry exactly the user's confirmed names and nothing else.
        assertTrue(rules.contains("豆豆"))
        assertTrue(rules.contains("小雨"))
        assertTrue(rules.contains("不要生成色情或露骨的性内容"))
        assertTrue(rules.contains("都保持未知，不得推断"))
        FORBIDDEN_IDENTITY_TOKENS.forEach { token ->
            assertFalse("identity rules must not contain $token", rules.contains(token))
        }
        // A fallback name is a placeholder, never a fixed product identity.
        val fallbackRules = CompanionPromptIdentity.identityRules(CompanionPromptIdentity.resolve(null))
        assertTrue(fallbackRules.contains(CompanionPromptIdentity.FALLBACK_COMPANION_NAME))
        assertEquals(
            CompanionPromptIdentity.FALLBACK_COMPANION_NAME,
            CompanionPromptIdentity.resolve(null).companionName,
        )
    }

    @Test
    fun releaseVerificationScriptChecksPublicEditionTokens() {
        val script = File(moduleRoot, "scripts/verify-public-release.sh")
        assertTrue("public release verification script must exist", script.isFile)
        val text = script.readText()
        // The old script asserted the retired private version name.
        assertFalse(text.contains("0.9.0-phase9"))
        // The script matches the version through a grep pattern, so the token is
        // escaped there.
        assertTrue(text.contains("1.0.0") || text.contains("1\\.0\\.0"))
        listOf("小可乐", "猫猫头", "SHIFT_STATIC_JSON_URL", "shift_cache", "DebugActivity").forEach { token ->
            assertTrue("verification script must scan for $token", text.contains(token))
        }
    }

    /**
     * Files whose declared purpose is to *reject* the forbidden vocabulary.
     * They must name the words to detect them, so they are exempt from the
     * "must not contain" scan; the exemption is per file and explicit.
     */
    private fun isSafetyPatternSource(relativePath: String): Boolean =
        relativePath.endsWith("firstcreate/PersonaGenerator.kt")

    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*") ||
            trimmed.startsWith("<!--")
    }

    /**
     * Boundary clauses necessarily name what they forbid. A line counts as a
     * prohibition only when the ban token sits directly behind a negating
     * instruction, so copy that merely *offers* an intimate mode is still
     * caught.
     */
    private fun isProhibitionLine(line: String): Boolean = line.contains(
        Regex("(不要|不得|严禁|禁止|不能|绝不|不生成|不推进)[^，。；]{0,8}(露骨|色情|调情|亲密|性内容|性暗示|勾引|暧昧)"),
    )

    private companion object {
        val SCANNED_EXTENSIONS = setOf("kt", "java", "xml", "kts")

        /**
         * Removed product identity and fixed user nicknames.
         *
         * These are the legacy product's own names. The neutral fallback
         * (`CompanionPromptIdentity.FALLBACK_COMPANION_NAME`) is deliberately
         * NOT listed: it is the permitted placeholder, not a fixed identity.
         */
        val FORBIDDEN_IDENTITY_TOKENS = listOf(
            "小可乐",
            "猫猫头",
            "专属小狗",
            "专属的",
        )

        /**
         * Relationship words a generic product must not assume on the user's
         * behalf. These may only appear inside a prohibition clause.
         */
        val FORBIDDEN_IMPOSED_RELATIONSHIP = listOf(
            "陪伴者",
            "恋人",
            "女朋友",
            "男朋友",
            "主人",
            "唯一",
        )

        /**
         * Physical appearance belonging to the retired private persona.
         *
         * Scoped deliberately: only traits the retired persona asserted about a
         * humanoid body are listed. Generic companion wording such as "尾巴"
         * (tail) is NOT listed, because the bundled animation set legitimately
         * renders a tail and a blanket ban would be a false positive; the
         * ambiguous cases are reported to the user instead of silently banned.
         */
        val FORBIDDEN_APPEARANCE_TOKENS = listOf(
            "长发",
            "马尾",
            "头发",
            "梳开",
            "扎起",
            "扎头发",
            "狗耳朵",
            "猫猫头",
        )

        /** Removed adult/intimate defaults, matched without their prohibition form. */
        val FORBIDDEN_ADULT_TOKENS = listOf(
            "露骨的成人张力",
            "可以更主动地调情",
            "主动调情",
            "勾引",
            "反撩",
            "已建立的自愿亲密语境",
            "具有完整同意能力的成年人",
            "亲密推进",
        )

        val PROHIBITION_MARKERS = listOf(
            "不要",
            "不得",
            "严禁",
            "禁止",
            "不能",
            "绝不",
            "拒绝",
            "不生成",
            "不推进",
            "保持未知",
            "尊重",
        )
    }
}
