package com.littlekitty.xiaokele.audit

import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.ExternalCacheDao
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Public-edition gate: the companion has no shift, no schedule and no
 * fixed-place geofence.
 *
 * This test is deliberately a *source* gate as well as a runtime gate. A build
 * can stop referencing a class while its file keeps existing in the tree, and a
 * file that exists is a file that can be re-wired later; the public edition
 * must not ship any of it.
 */
class PublicEditionNoScheduleTest {

    private val moduleRoot: File by lazy {
        // Tests run with the module directory as the working directory; fall
        // back to walking up so the gate also works from an IDE runner.
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not locate project root from ${File("").absolutePath}")
    }

    private val scannedSourceRoots: List<File> by lazy {
        listOf(
            File(moduleRoot, "app/src/main"),
            File(moduleRoot, "app/src/debug"),
            File(moduleRoot, "core/common/src/main"),
            File(moduleRoot, "core/database/src/main"),
            File(moduleRoot, "core/network/src/main"),
        ).filter { it.isDirectory }
    }

    @Test
    fun noShiftOrGeofenceSourcesExistInTheProject() {
        val offenders = scannedSourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.extension in SCANNED_EXTENSIONS }
            .filter { file -> forbiddenPathToken(file.relativeTo(moduleRoot).path) != null }
            .map { it.relativeTo(moduleRoot).path }
            .sorted()

        assertTrue(
            "public edition must not contain shift/schedule/geofence sources: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun noShiftOrScheduleIdentifiersRemainInProductionSource() {
        val hits = mutableListOf<String>()
        scannedSourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.extension in SCANNED_EXTENSIONS }
            .forEach { file ->
                val relative = file.relativeTo(moduleRoot).path
                file.readLines().forEachIndexed { index, line ->
                    // Comments may name the removed feature when explaining a
                    // migration; only executable source counts.
                    if (isCommentLine(line)) return@forEachIndexed
                    // The one legitimate mention is the migration that removes
                    // the legacy table, which cannot reference it any other way.
                    if (isShiftTableDropStatement(line)) return@forEachIndexed
                    FORBIDDEN_IDENTIFIERS.forEach { identifier ->
                        if (line.contains(identifier)) {
                            hits += "$relative:${index + 1}: $identifier"
                        }
                    }
                }
            }

        assertTrue(
            "shift/schedule identifiers must not appear in the public edition:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * The 11 -> 12 migration must name `shift_cache` to drop it. Every other
     * mention is forbidden, so the exemption is exactly this one statement.
     */
    private fun isShiftTableDropStatement(line: String): Boolean =
        line.contains("DROP TABLE IF EXISTS") && line.contains("shift_cache")

    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*") ||
            trimmed.startsWith("<!--")
    }

    @Test
    fun currentContextCarriesNoShiftOrPlaceContext() {
        val fields = CurrentContext::class.java.declaredFields.map { it.name }

        assertFalse("CurrentContext must not expose shiftPhase", fields.contains("shiftPhase"))
        assertFalse("CurrentContext must not expose placeType", fields.contains("placeType"))
        assertFalse("CurrentContext must not expose activeShift", fields.contains("activeShift"))
        assertFalse("CurrentContext must not expose activeMealWindow", fields.contains("activeMealWindow"))
        assertFalse("CurrentContext must not expose scheduleState", fields.contains("scheduleState"))
        assertFalse("CurrentContext must not expose scheduleStateNote", fields.contains("scheduleStateNote"))

        // The fields that must survive, so this gate cannot pass by deleting the DTO.
        assertTrue(fields.contains("localTime"))
        assertTrue(fields.contains("petState"))
        assertTrue(fields.contains("weather"))
        assertTrue(fields.contains("petDailyLife"))
    }

    @Test
    fun databaseHasNoShiftDaoOrShiftTableEntity() {
        val daoMethods = AppDatabase::class.java.declaredMethods.map { it.name }
        assertFalse("AppDatabase must not expose a shift DAO", daoMethods.contains("shiftCacheDao"))

        val appSources = File(moduleRoot, "core/database/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        assertTrue(
            "core/database must not declare a ShiftCacheEntity",
            appSources.none { it.contains("ShiftCacheEntity") },
        )
        assertTrue(
            "core/database must not create a shift_cache table",
            appSources.none { it.contains("tableName = \"shift_cache\"") },
        )
    }

    @Test
    fun migrationDropsTheLegacyShiftTable() {
        val appDatabase = File(
            moduleRoot,
            "core/database/src/main/java/com/littlekitty/xiaokele/core/database/AppDatabase.kt",
        ).readText()

        assertTrue(
            "a migration must drop shift_cache so cached schedule rows cannot be restored",
            appDatabase.contains("DROP TABLE IF EXISTS `shift_cache`"),
        )
        assertTrue("schema version must be 12", appDatabase.contains("const val VERSION = 12"))
    }

    @Test
    fun backgroundLocationPermissionIsAbsent() {
        val manifest = File(moduleRoot, "app/src/main/AndroidManifest.xml").readText()

        assertFalse(
            "public edition must not request background location",
            manifest.contains("ACCESS_BACKGROUND_LOCATION"),
        )
        assertTrue(
            "foreground location is still required for local life",
            manifest.contains("ACCESS_COARSE_LOCATION"),
        )
    }

    @Test
    fun externalCacheDaoSurvivesBecauseWeatherAndLocalLifeStillUseIt() {
        val methods = ExternalCacheDao::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("byKeyAndSource"))
        assertTrue(methods.contains("insertIfFresher"))
    }

    /** Release-hygiene style path gate: the file must not even exist. */
    private fun forbiddenPathToken(relativePath: String): String? {
        val name = relativePath.substringAfterLast('/').lowercase()
        return FORBIDDEN_PATH_TOKENS.firstOrNull { name.contains(it) }
    }

    private companion object {
        val SCANNED_EXTENSIONS = setOf("kt", "java", "xml", "kts")

        /**
         * Identifiers that may not appear anywhere in production source. These
         * are the names of the removed shift/schedule/geofence feature; keeping
         * any of them means a re-wireable remnant is still shipping.
         */
        val FORBIDDEN_IDENTIFIERS = listOf(
            "ShiftCache",
            "shiftCache",
            "shift_cache",
            "ScheduleJson",
            "ScheduleSettingsStore",
            "ScheduleSnapshotStore",
            "ScheduleDownloadPlanner",
            "ScheduleUrlPolicy",
            "ScheduleSceneEvent",
            "SHIFT_UPCOMING",
            "SHIFT_START",
            "SHIFT_END",
            "SHIFT_MEAL_WINDOW",
            "SHIFT_PHASE",
            "LocationGeofence",
            "LocationConfigParser",
            "LocationStateTracker",
            "LocationWindowWorker",
            "LocationTransition",
            "LOCATION_TRANSITION",
            "PLACE_HOSPITAL",
            "PLACE_HOME",
            "nearbyCandidateSearchEnabled",
        )

        /**
         * A source file whose name carries these tokens is a removed feature.
         * "scheduler"/"schedule" are legitimate infrastructure words
         * (WorkManager, wake topics, automation time rules), so only the
         * shift-specific file names are matched.
         */
        val FORBIDDEN_PATH_TOKENS = listOf(
            "schedulejson",
            "scheduledownloadplanner",
            "shiftcache",
            "shiftsync",
            "geofence",
            "locationwindow",
            "locationstatetracker",
            "locationgeofence",
        )
    }
}
