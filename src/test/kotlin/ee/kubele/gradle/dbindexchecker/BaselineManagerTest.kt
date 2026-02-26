package ee.kubele.gradle.dbindexchecker

import ee.kubele.gradle.dbindexchecker.model.BaselineIssue
import ee.kubele.gradle.dbindexchecker.model.MissingIndex
import ee.kubele.gradle.dbindexchecker.model.QueryType
import ee.kubele.gradle.dbindexchecker.report.BaselineManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaselineManagerTest {

    @Test
    fun `compare splits issues into new existing and resolved`() {
        val current = listOf(
            missing(service = "svc-a", table = "users", column = "email"),
            missing(service = "svc-a", table = "orders", column = "status")
        )
        val baseline = listOf(
            BaselineIssue(serviceName = "svc-a", tableName = "users", columnName = "email"),
            BaselineIssue(serviceName = "svc-b", tableName = "audit_log", columnName = "created_at")
        )

        val comparison = BaselineManager.compare(current, baseline)

        assertEquals(1, comparison.newIssues.size)
        assertEquals("orders", comparison.newIssues.first().tableName)
        assertEquals(1, comparison.existingIssues.size)
        assertEquals("users", comparison.existingIssues.first().tableName)
        assertEquals(1, comparison.resolvedIssues.size)
        assertEquals("svc-b", comparison.resolvedIssues.first().serviceName)
    }

    @Test
    fun `baseline write and read keeps unique issues`() {
        val dir = createTempDirectory("baseline-test").toFile()
        val file = File(dir, "db-index-checker-baseline.json")
        try {
            val current = listOf(
                missing(service = "svc-a", table = "users", column = "email"),
                missing(service = "svc-a", table = "users", column = "email"),
                missing(service = "svc-a", table = "users", column = "name")
            )

            BaselineManager.writeBaseline(file, current)
            val baseline = BaselineManager.readBaseline(file)

            assertEquals(2, baseline.size)
            assertTrue(baseline.any { it.tableName == "users" && it.columnName == "email" })
            assertTrue(baseline.any { it.tableName == "users" && it.columnName == "name" })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun missing(service: String, table: String, column: String): MissingIndex =
        MissingIndex(
            serviceName = service,
            tableName = table,
            columnName = column,
            querySource = "findBy$column",
            repositoryFile = "/tmp/Repo.kt",
            lineNumber = 1,
            queryType = QueryType.DERIVED_QUERY
        )
}
