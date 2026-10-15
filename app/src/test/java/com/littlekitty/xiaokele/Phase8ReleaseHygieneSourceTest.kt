package com.littlekitty.xiaokele

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase8ReleaseHygieneSourceTest {

    @Test
    fun releaseSourceSetsContainNoDebugFakesDebugEntriesCoordinatesOrRealSecrets() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val mainSets = listOf(
            "app/src/main",
            "core/common/src/main",
            "core/database/src/main",
            "core/network/src/main",
        ).map { File(root, it) }

        val files = mainSets.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile }
                .filter { it.extension in setOf("kt", "java", "xml", "kts") }
                .toList()
        }
        assertTrue("expected production source files", files.isNotEmpty())

        val forbiddenTokens = listOf(
            "FakeModelService",
            "FakeMemoryEvaluator",
            "FakeEmbeddingService",
            "DebugSceneModelService",
            "DebugActivity",
            "DEBUG_FAKE",
            "debug_entry",
        )
        val coordinate = Regex("[0-9]+\\.[0-9]{4,}")
        val realSecrets = listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Regex("Bearer\\s+[A-Za-z0-9._\\-]{12,}"),
            Regex("sk-[A-Za-z0-9]{12,}"),
            Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        )

        files.forEach { file ->
            val text = file.readText()
            forbiddenTokens.forEach { token ->
                assertTrue("forbidden token $token in ${file.relativeTo(root)}", text.contains(token).not())
            }
            assertTrue("coordinate literal in ${file.relativeTo(root)}", coordinate.containsMatchIn(text).not())
            realSecrets.forEach { pattern ->
                assertTrue("real secret pattern in ${file.relativeTo(root)}", pattern.containsMatchIn(text).not())
            }
        }
    }
}
