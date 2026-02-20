package cz.kubele.gradle.dbindexchecker

import cz.kubele.gradle.dbindexchecker.parser.EntityParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityParserTest {

    @Test
    fun `parseEntities returns empty map for non-existent directory`() {
        val result = EntityParser.parseEntities(File("/nonexistent/path"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseEntities returns empty map for directory with no entity files`() {
        val dir = createTempDir("entity-test")
        try {
            dir.resolve("NotAnEntity.kt").writeText("""
                package com.example
                class NotAnEntity(val name: String)
            """.trimIndent())
            val result = EntityParser.parseEntities(dir)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseEntityFile returns null for file without Entity annotation`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example
                class NotAnEntity(val name: String)
            """.trimIndent())
            val result = EntityParser.parseEntityFile(file)
            assertNull(result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile returns null for entity without Table annotation`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example
                @Entity
                class MyEntity(val name: String)
            """.trimIndent())
            val result = EntityParser.parseEntityFile(file)
            assertNull(result)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile parses simple entity with Table and Column annotations`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "users")
                class User {
                    @Column(name = "user_name")
                    var userName: String = ""

                    @Column(name = "email_address")
                    var email: String = ""
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("User", result.entityName)
            assertEquals("users", result.tableName)
            assertEquals("user_name", result.fieldToColumn["userName"])
            assertEquals("email_address", result.fieldToColumn["email"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile uses default camelToSnake naming for unannotated fields`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "orders")
                class Order {
                    var orderDate: String = ""
                    var totalAmount: Double = 0.0
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("orders", result.tableName)
            assertEquals("order_date", result.fieldToColumn["orderDate"])
            assertEquals("total_amount", result.fieldToColumn["totalAmount"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile parses JoinColumn annotation`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "orders")
                class Order {
                    @ManyToOne
                    @JoinColumn(name = "customer_id")
                    var customer: Customer? = null
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("customer_id", result.fieldToColumn["customer"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile skips OneToMany and ManyToMany fields`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "customers")
                class Customer {
                    @Column(name = "full_name")
                    var fullName: String = ""

                    @OneToMany
                    var orders: List<Order> = emptyList()

                    @ManyToMany
                    var tags: Set<Tag> = emptySet()
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("full_name", result.fieldToColumn["fullName"])
            // OneToMany and ManyToMany fields should not be in fieldToColumn
            assertNull(result.fieldToColumn["orders"])
            assertNull(result.fieldToColumn["tags"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile always includes id field`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "items")
                class Item {
                    var name: String = ""
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("id", result.fieldToColumn["id"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntityFile adds createdAt and updatedAt defaults`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "items")
                class Item {
                    var name: String = ""
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("created_at", result.fieldToColumn["createdAt"])
            assertEquals("updated_at", result.fieldToColumn["updatedAt"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parseEntities finds multiple entities in directory`() {
        val dir = createTempDir("entity-test")
        try {
            dir.resolve("User.kt").writeText("""
                package com.example
                @Entity
                @Table(name = "users")
                class User {
                    var name: String = ""
                }
            """.trimIndent())

            dir.resolve("Order.kt").writeText("""
                package com.example
                @Entity
                @Table(name = "orders")
                class Order {
                    var total: Double = 0.0
                }
            """.trimIndent())

            val result = EntityParser.parseEntities(dir)
            assertEquals(2, result.size)
            assertNotNull(result["User"])
            assertNotNull(result["Order"])
            assertEquals("users", result["User"]!!.tableName)
            assertEquals("orders", result["Order"]!!.tableName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseEntities walks subdirectories`() {
        val dir = createTempDir("entity-test")
        try {
            val subDir = dir.resolve("sub")
            subDir.mkdirs()
            subDir.resolve("Product.kt").writeText("""
                package com.example
                @Entity
                @Table(name = "products")
                class Product {
                    var price: Double = 0.0
                }
            """.trimIndent())

            val result = EntityParser.parseEntities(dir)
            assertEquals(1, result.size)
            assertNotNull(result["Product"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `camelToSnake converts correctly`() {
        assertEquals("order_date", EntityParser.camelToSnake("orderDate"))
        assertEquals("total_amount", EntityParser.camelToSnake("totalAmount"))
        assertEquals("name", EntityParser.camelToSnake("name"))
        assertEquals("created_at_time", EntityParser.camelToSnake("createdAtTime"))
        assertEquals("i_d", EntityParser.camelToSnake("iD"))
    }

    @Test
    fun `parseEntityFile skips JoinTable fields`() {
        val file = createTempFile("test", ".kt")
        try {
            file.writeText("""
                package com.example

                @Entity
                @Table(name = "students")
                class Student {
                    @Column(name = "student_name")
                    var studentName: String = ""

                    @ManyToMany
                    @JoinTable
                    var courses: Set<Course> = emptySet()
                }
            """.trimIndent())

            val result = EntityParser.parseEntityFile(file)
            assertNotNull(result)
            assertEquals("student_name", result.fieldToColumn["studentName"])
            assertNull(result.fieldToColumn["courses"])
        } finally {
            file.delete()
        }
    }

    private fun createTempDir(prefix: String): File {
        val dir = kotlin.io.path.createTempDirectory(prefix).toFile()
        return dir
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix)
    }
}
