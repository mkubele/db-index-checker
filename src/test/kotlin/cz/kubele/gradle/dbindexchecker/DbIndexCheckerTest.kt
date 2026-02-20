package cz.kubele.gradle.dbindexchecker

import cz.kubele.gradle.dbindexchecker.checker.DbIndexChecker
import cz.kubele.gradle.dbindexchecker.model.IndexedColumn
import cz.kubele.gradle.dbindexchecker.model.QueryColumn
import cz.kubele.gradle.dbindexchecker.model.QueryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbIndexCheckerTest {

    @Test
    fun `findMissingIndexes returns empty when no query columns`() {
        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = emptyList(),
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes reports column without index`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertEquals(1, result.size)
        assertEquals("test-service", result[0].serviceName)
        assertEquals("users", result[0].tableName)
        assertEquals("email", result[0].columnName)
        assertEquals("findByEmail", result[0].querySource)
        assertEquals("/test/UserRepo.kt", result[0].repositoryFile)
        assertEquals(10, result[0].lineNumber)
        assertEquals(QueryType.DERIVED_QUERY, result[0].queryType)
    }

    @Test
    fun `findMissingIndexes does not report column with index`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_users_email",
                filePath = "/liquibase/changelog.xml"
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes skips id column`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "id",
                source = "findById",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes respects excludeTables`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "audit_log",
                columnName = "event_type",
                source = "findByEventType",
                filePath = "/test/AuditRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = setOf("audit_log"),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes respects excludeColumns`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "created_at",
                source = "findByCreatedAt",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = setOf("created_at")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes case-insensitive table matching`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "Users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_users_email",
                filePath = "/liquibase/changelog.xml"
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes case-insensitive column matching`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "Email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_users_email",
                filePath = "/liquibase/changelog.xml"
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes deduplicates by table and column`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmailAndName",
                filePath = "/test/UserRepo.kt",
                lineNumber = 15,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `findMissingIndexes considers composite index position 0 as indexed`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "name",
                source = "findByName",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "name",
                indexName = "idx_users_name_email",
                filePath = "/liquibase/changelog.xml",
                compositePosition = 0
            ),
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_users_name_email",
                filePath = "/liquibase/changelog.xml",
                compositePosition = 1
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        // name is at position 0 of composite index, so it should be considered indexed
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes does not consider composite index position 1 as indexed`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "name",
                indexName = "idx_users_name_email",
                filePath = "/liquibase/changelog.xml",
                compositePosition = 0
            ),
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_users_name_email",
                filePath = "/liquibase/changelog.xml",
                compositePosition = 1
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        // email is at position 1, so it's not usable as a standalone index lookup
        assertEquals(1, result.size)
        assertEquals("email", result[0].columnName)
    }

    @Test
    fun `findMissingIndexes results are sorted by table then column`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "orders",
                columnName = "total",
                source = "findByTotal",
                filePath = "/test/OrderRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "orders",
                columnName = "date",
                source = "findByDate",
                filePath = "/test/OrderRepo.kt",
                lineNumber = 15,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "active",
                source = "findByActive",
                filePath = "/test/UserRepo.kt",
                lineNumber = 20,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertEquals(4, result.size)
        assertEquals("orders", result[0].tableName)
        assertEquals("date", result[0].columnName)
        assertEquals("orders", result[1].tableName)
        assertEquals("total", result[1].columnName)
        assertEquals("users", result[2].tableName)
        assertEquals("active", result[2].columnName)
        assertEquals("users", result[3].tableName)
        assertEquals("email", result[3].columnName)
    }

    @Test
    fun `findMissingIndexes case-insensitive exclude tables`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "Audit_Log",
                columnName = "event_type",
                source = "findByEventType",
                filePath = "/test/AuditRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = setOf("audit_log"),
            excludeColumns = emptySet()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes case-insensitive exclude columns`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "Created_At",
                source = "findByCreatedAt",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = setOf("created_at")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes respects excludeFindings`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "name",
                source = "findByName",
                filePath = "/test/UserRepo.kt",
                lineNumber = 15,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet(),
            excludeFindings = setOf("users.email")
        )

        assertEquals(1, result.size)
        assertEquals("name", result[0].columnName)
    }

    @Test
    fun `findMissingIndexes excludeFindings is case-insensitive`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "Users",
                columnName = "Email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet(),
            excludeFindings = setOf("users.email")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findMissingIndexes excludeFindings does not affect other table-column pairs`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "orders",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/OrderRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = emptyList(),
            excludeTables = emptySet(),
            excludeColumns = emptySet(),
            excludeFindings = setOf("users.email")
        )

        assertEquals(1, result.size)
        assertEquals("orders", result[0].tableName)
        assertEquals("email", result[0].columnName)
    }

    @Test
    fun `findMissingIndexes mixed scenario - some indexed some not`() {
        val queryColumns = listOf(
            QueryColumn(
                tableName = "users",
                columnName = "email",
                source = "findByEmail",
                filePath = "/test/UserRepo.kt",
                lineNumber = 10,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "name",
                source = "findByName",
                filePath = "/test/UserRepo.kt",
                lineNumber = 15,
                queryType = QueryType.DERIVED_QUERY
            ),
            QueryColumn(
                tableName = "users",
                columnName = "active",
                source = "findByActive",
                filePath = "/test/UserRepo.kt",
                lineNumber = 20,
                queryType = QueryType.DERIVED_QUERY
            )
        )

        val indexedColumns = listOf(
            IndexedColumn(
                tableName = "users",
                columnName = "email",
                indexName = "idx_email",
                filePath = "/liquibase/changelog.xml"
            )
        )

        val result = DbIndexChecker.findMissingIndexes(
            serviceName = "test-service",
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = emptySet(),
            excludeColumns = emptySet()
        )

        assertEquals(2, result.size)
        val missingCols = result.map { it.columnName }.toSet()
        assertTrue("name" in missingCols)
        assertTrue("active" in missingCols)
    }
}
