package cz.kubele.gradle.dbindexchecker

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbIndexCheckerPluginFunctionalTest {

    @Test
    fun `dbIndexCheck supports write-baseline option`() {
        val projectDir = Files.createTempDirectory("db-index-checker-functional").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "test-project"
                """.trimIndent()
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("cz.kubele.db-index-checker")
                }
                """.trimIndent()
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("dbIndexCheck", "--write-baseline")
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":dbIndexCheck")?.outcome)
            assertTrue(projectDir.resolve("db-index-checker-baseline.json").exists())
            assertTrue(projectDir.resolve("build/reports/index-check/missing-indexes.html").exists())
            assertTrue(projectDir.resolve("build/reports/index-check/missing-indexes.json").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
