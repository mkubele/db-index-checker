package ee.kubele.gradle.dbindexchecker

import ee.kubele.gradle.dbindexchecker.model.QueryType
import ee.kubele.gradle.dbindexchecker.model.TableMapping
import ee.kubele.gradle.dbindexchecker.parser.RepositoryParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryParserTest {

    private val userMapping = TableMapping(
        entityName = "User",
        tableName = "users",
        fieldToColumn = mapOf(
            "id" to "id",
            "name" to "name",
            "email" to "email_address",
            "createdAt" to "created_at",
            "active" to "active",
            "age" to "age",
            "lastName" to "last_name"
        )
    )

    private val entityMappings = mapOf("User" to userMapping)

    @Test
    fun `parseRepositories returns empty list for non-existent directory`() {
        val result = RepositoryParser.parseRepositories(File("/nonexistent/path"), entityMappings)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseRepositories returns empty list for file without repository`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("NotARepo.kt").writeText("""
                package com.example
                class NotARepo {
                    fun findSomething(): String = ""
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseRepositories returns empty when entity not in mappings`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("OrderRepo.kt").writeText("""
                package com.example
                interface OrderRepository : CrudRepository<Order, Long> {
                    fun findByStatus(status: String): List<Order>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // === Derived Query Tests ===

    @Test
    fun `derived query - simple findBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("name", result[0].columnName)
            assertEquals(QueryType.DERIVED_QUERY, result[0].queryType)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - findBy with mapped column name`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByEmail(email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("email_address", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - findBy with And`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByNameAndEmail(name: String, email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(2, result.size)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("name" in columns)
            assertTrue("email_address" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - existsBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun existsByEmail(email: String): Boolean
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("email_address", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - countBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun countByActive(active: Boolean): Long
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("active", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - deleteBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun deleteByActive(active: Boolean): Long
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("active", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - with condition suffix IsNull`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByCreatedAtIsNull(): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("created_at", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - with OrderBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByActiveOrderByCreatedAtDesc(active: Boolean): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(2, result.size)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("active" in columns)
            assertTrue("created_at" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - findFirstBy`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findFirstByName(name: String): User?
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - non-derived method is ignored`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun save(user: User): User
                    fun customMethod(): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - with Between suffix`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByAgeBetween(min: Int, max: Int): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("age", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - with In suffix`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByNameIn(names: List<String>): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    // === JPQL Query Tests ===

    @Test
    fun `JPQL query - simple WHERE clause`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT u FROM User u WHERE u.name = :name")
                    fun findUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
            assertEquals(QueryType.JPQL, result[0].queryType)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JPQL query - multiple fields in WHERE`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT u FROM User u WHERE u.name = :name AND u.email = :email")
                    fun findUserByNameAndEmail(name: String, email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(2, result.size)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("name" in columns)
            assertTrue("email_address" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JPQL query - triple quoted string`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query(${"\"\"\""}SELECT u FROM User u WHERE u.active = :active${"\"\"\""})
                    fun findActiveUsers(active: Boolean): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("active", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JPQL query - method preceded by Query should not generate derived query results`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT u FROM User u WHERE u.name = :name")
                    fun findByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            // Should only have JPQL results, not derived query results
            assertTrue(result.all { it.queryType == QueryType.JPQL })
        } finally {
            dir.deleteRecursively()
        }
    }

    // === Native SQL Query Tests ===

    @Test
    fun `native SQL query - simple WHERE clause`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT * FROM users WHERE name = :name", nativeQuery = true)
                    fun findUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
            assertEquals(QueryType.NATIVE_SQL, result[0].queryType)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `native SQL query - multiple WHERE conditions`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT * FROM users WHERE name = :name AND active = true", nativeQuery = true)
                    fun findActiveUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("name" in columns)
            assertTrue("active" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `native SQL query - with ORDER BY`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT * FROM users WHERE active = true ORDER BY created_at DESC", nativeQuery = true)
                    fun findActiveUsersOrdered(): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("active" in columns)
            assertTrue("created_at" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `native SQL query - with JOIN ON only reports entity table columns`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    @Query("SELECT u.* FROM users u JOIN orders o ON u.user_ref = o.customer_id WHERE u.active = true", nativeQuery = true)
                    fun findUsersWithOrders(): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            val columns = result.map { it.columnName }.toSet()
            // Columns qualified with entity table alias 'u' should be reported
            assertTrue("active" in columns, "WHERE column from entity table should be reported")
            assertTrue("user_ref" in columns, "JOIN ON column from entity table should be reported")
            // Column qualified with non-entity alias 'o' should NOT be reported
            assertTrue("customer_id" !in columns, "JOIN ON column from other table should not be reported")
        } finally {
            dir.deleteRecursively()
        }
    }

    // === Mixed query types ===

    @Test
    fun `multiple query types in same repository`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByName(name: String): List<User>

                    @Query("SELECT u FROM User u WHERE u.email = :email")
                    fun findUserByEmail(email: String): List<User>

                    @Query("SELECT * FROM users WHERE active = :active", nativeQuery = true)
                    fun findActiveUsers(active: Boolean): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.any { it.queryType == QueryType.DERIVED_QUERY })
            assertTrue(result.any { it.queryType == QueryType.JPQL })
            assertTrue(result.any { it.queryType == QueryType.NATIVE_SQL })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JpaRepository is detected`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : JpaRepository<User, Long> {
                    fun findByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `PagingAndSortingRepository is detected`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : PagingAndSortingRepository<User, Long> {
                    fun findByEmail(email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("email_address", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseRepositories walks subdirectories`() {
        val dir = createTempDir("repo-test")
        try {
            val subDir = dir.resolve("sub")
            subDir.mkdirs()
            subDir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `derived query - with Or separator`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    fun findByNameOrEmail(name: String, email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(2, result.size)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("name" in columns)
            assertTrue("email_address" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    // === @SuppressIndexCheck Tests ===

    @Test
    fun `SuppressIndexCheck suppresses all columns from derived query`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck
                    fun findByNameAndEmail(name: String, email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck with specific columns suppresses only those columns`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck("name")
                    fun findByNameAndEmail(name: String, email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("email_address", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck with multiple specific columns`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck("name", "email_address")
                    fun findByNameAndEmailAndActive(name: String, email: String, active: Boolean): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("active", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck only affects the next method`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck
                    fun findByName(name: String): List<User>

                    fun findByEmail(email: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("email_address", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck works with JPQL query`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck
                    @Query("SELECT u FROM User u WHERE u.name = :name")
                    fun findUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck works with native SQL query`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck
                    @Query("SELECT * FROM users WHERE name = :name", nativeQuery = true)
                    fun findUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SuppressIndexCheck with specific columns works with native SQL query`() {
        val dir = createTempDir("repo-test")
        try {
            dir.resolve("UserRepo.kt").writeText("""
                package com.example
                interface UserRepository : CrudRepository<User, Long> {
                    // @SuppressIndexCheck("name")
                    @Query("SELECT * FROM users WHERE name = :name AND active = true", nativeQuery = true)
                    fun findActiveUserByName(name: String): List<User>
                }
            """.trimIndent())
            val result = RepositoryParser.parseRepositories(dir, entityMappings)
            assertEquals(1, result.size)
            assertEquals("active", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        return kotlin.io.path.createTempDirectory(prefix).toFile()
    }
}
