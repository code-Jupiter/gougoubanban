package com.littlekitty.xiaokele

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase9ReleaseContractTest {

    @Test
    fun minimaxKeyIsNotABuildConfigFieldOrDependency() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val gradle = File(root, "app/build.gradle.kts").readText()
        val appModule = File(root, "app/src/main/java/com/littlekitty/xiaokele/di/AppModule.kt").readText()
        assertTrue(gradle.contains("MINIMAX_CHAT_API_KEY").not())
        assertTrue(appModule.contains("MiniMaxChatServiceAdapter").not())
    }

    @Test
    fun debugAllowsLocalCleartextAndReleaseDoesNot() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val release = File(root, "app/src/main/res/xml/network_security_config.xml").readText()
        val debug = File(root, "app/src/debug/res/xml/network_security_config.xml").readText()
        assertTrue(release.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(debug.contains("cleartextTrafficPermitted=\"true\""))
    }
}
