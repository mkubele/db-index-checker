package cz.kubele.gradle.dbindexchecker

import cz.kubele.gradle.dbindexchecker.parser.LiquibaseParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquibaseParserTest {

    @Test
    fun `parseIndexes returns empty list when changelog does not exist`() {
        val dir = createTempDir("liquibase-test")
        try {
            val result = LiquibaseParser.parseIndexes(dir)
            assertTrue(result.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses createIndex element`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createIndex tableName="users" indexName="idx_users_email">
                            <column name="email"/>
                        </createIndex>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("email", result[0].columnName)
            assertEquals("idx_users_email", result[0].indexName)
            assertFalse(result[0].isUnique)
            assertEquals(0, result[0].compositePosition)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses unique createIndex`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createIndex tableName="users" indexName="idx_users_email_unique" unique="true">
                            <column name="email"/>
                        </createIndex>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses composite createIndex`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createIndex tableName="users" indexName="idx_users_name_email">
                            <column name="name"/>
                            <column name="email"/>
                        </createIndex>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(2, result.size)
            val first = result.find { it.compositePosition == 0 }!!
            val second = result.find { it.compositePosition == 1 }!!
            assertEquals("name", first.columnName)
            assertEquals("email", second.columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses addUniqueConstraint`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <addUniqueConstraint tableName="users" columnNames="email" constraintName="uq_users_email"/>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("email", result[0].columnName)
            assertEquals("uq_users_email", result[0].indexName)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses addUniqueConstraint with multiple columns`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <addUniqueConstraint tableName="users" columnNames="first_name, last_name" constraintName="uq_users_name"/>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(2, result.size)
            assertEquals("first_name", result[0].columnName)
            assertEquals("last_name", result[1].columnName)
            assertEquals(0, result[0].compositePosition)
            assertEquals(1, result[1].compositePosition)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses SQL CREATE INDEX`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX idx_users_name ON users(name);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("name", result[0].columnName)
            assertEquals("idx_users_name", result[0].indexName)
            assertFalse(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses SQL CREATE UNIQUE INDEX`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE UNIQUE INDEX idx_users_email ON users(email);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses SQL CREATE INDEX CONCURRENTLY`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX CONCURRENTLY idx_users_name ON users(name);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses SQL CREATE INDEX IF NOT EXISTS`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX IF NOT EXISTS idx_users_name ON users(name);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes detects partial index with WHERE clause`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX idx_users_active ON users(name) WHERE active = true;</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertTrue(result[0].isPartial)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes skips SQL in rollback elements`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX idx_users_name ON users(name);</sql>
                        <rollback>
                            <sql>CREATE INDEX idx_rollback ON rollback_table(col);</sql>
                        </rollback>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            // Should only have the index from the main SQL, not the rollback
            assertEquals(1, result.size)
            assertEquals("idx_users_name", result[0].indexName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses primary key from createTable`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createTable tableName="users">
                            <column name="id" type="bigint">
                                <constraints primaryKey="true" primaryKeyName="pk_users"/>
                            </column>
                            <column name="name" type="varchar(255)"/>
                        </createTable>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("id", result[0].columnName)
            assertEquals("pk_users", result[0].indexName)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses unique constraint from createTable`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createTable tableName="users">
                            <column name="email" type="varchar(255)">
                                <constraints unique="true" uniqueConstraintName="uq_email"/>
                            </column>
                        </createTable>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("email", result[0].columnName)
            assertEquals("uq_email", result[0].indexName)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes parses unique constraint from addColumn`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <addColumn tableName="users">
                            <column name="phone" type="varchar(20)">
                                <constraints unique="true" uniqueConstraintName="uq_phone"/>
                            </column>
                        </addColumn>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("users", result[0].tableName)
            assertEquals("phone", result[0].columnName)
            assertEquals("uq_phone", result[0].indexName)
            assertTrue(result[0].isUnique)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes follows include references`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <include file="indexes.xml" relativeToChangelogFile="true"/>
                </databaseChangeLog>
            """.trimIndent())

            dir.resolve("indexes.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createIndex tableName="users" indexName="idx_users_name">
                            <column name="name"/>
                        </createIndex>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(1, result.size)
            assertEquals("name", result[0].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes handles multiple index types in single changelog`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createIndex tableName="users" indexName="idx_name">
                            <column name="name"/>
                        </createIndex>
                    </changeSet>
                    <changeSet id="2" author="test">
                        <addUniqueConstraint tableName="users" columnNames="email" constraintName="uq_email"/>
                    </changeSet>
                    <changeSet id="3" author="test">
                        <sql>CREATE INDEX idx_users_active ON users(active);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(3, result.size)
            val columns = result.map { it.columnName }.toSet()
            assertTrue("name" in columns)
            assertTrue("email" in columns)
            assertTrue("active" in columns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes handles SQL with ASC DESC in column list`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <sql>CREATE INDEX idx_users_time ON users(created_at DESC, name ASC);</sql>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            assertEquals(2, result.size)
            assertEquals("created_at", result[0].columnName)
            assertEquals("name", result[1].columnName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `parseIndexes does not count createTable primaryKey column as unique constraint`() {
        val dir = createTempDir("liquibase-test")
        try {
            dir.resolve("changelog.xml").writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <changeSet id="1" author="test">
                        <createTable tableName="users">
                            <column name="id" type="bigint">
                                <constraints primaryKey="true" unique="true" primaryKeyName="pk_users"/>
                            </column>
                        </createTable>
                    </changeSet>
                </databaseChangeLog>
            """.trimIndent())

            val result = LiquibaseParser.parseIndexes(dir)
            // Primary key should be counted once (from parsePrimaryKeys), and
            // parseCreateTableUniqueConstraints should skip it because primaryKey="true"
            val idColumns = result.filter { it.columnName == "id" }
            assertEquals(1, idColumns.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File {
        return kotlin.io.path.createTempDirectory(prefix).toFile()
    }
}
