package cz.kubele.gradle.dbindexchecker.parser

import cz.kubele.gradle.dbindexchecker.model.IndexedColumn
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses Liquibase XML changelog files to extract all indexed columns.
 * Handles createIndex, addUniqueConstraint, raw SQL CREATE INDEX, primary keys,
 * and unique constraints on column definitions.
 */
object LiquibaseParser {

    private val dbFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }

    /**
     * Regex for SQL CREATE INDEX statements. Case-insensitive.
     * Captures: optional UNIQUE, index name, table name, column list (before optional INCLUDE/WHERE).
     */
    private val createIndexSqlRegex = Regex(
        """CREATE\s+(UNIQUE\s+)?INDEX\s+(?:CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?(\S+)\s+ON\s+(\S+)\s*\(([^)]+)\)""",
        RegexOption.IGNORE_CASE
    )

    fun parseIndexes(liquibaseDir: File): List<IndexedColumn> {
        val changelogFile = liquibaseDir.resolve("changelog.xml")
        if (!changelogFile.exists()) return emptyList()

        return buildList {
            val visited = mutableSetOf<String>()
            parseChangelogFile(changelogFile, this, visited)
        }
    }

    private fun parseChangelogFile(
        file: File,
        results: MutableList<IndexedColumn>,
        visited: MutableSet<String>
    ) {
        val canonicalPath = file.canonicalPath
        if (!visited.add(canonicalPath)) return
        if (!file.exists()) return

        val doc = try {
            dbFactory.newDocumentBuilder().parse(file)
        } catch (_: Exception) {
            return
        }

        val parentDir = file.parentFile

        // Follow <include> references — relativeToChangelogFile always resolves from parent
        forEachElement(doc, "include") { includeElem ->
            val includedFilePath = includeElem.getAttribute("file")
            if (includedFilePath.isNotBlank()) {
                parseChangelogFile(parentDir.resolve(includedFilePath), results, visited)
            }
        }

        // Parse index definitions in this file
        parseFileForIndexes(doc, file.path, results)
    }

    private fun parseFileForIndexes(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        parseCreateIndex(doc, filePath, results)
        parseAddUniqueConstraint(doc, filePath, results)
        parseSqlElements(doc, filePath, results)
        parsePrimaryKeys(doc, filePath, results)
        parseCreateTableUniqueConstraints(doc, filePath, results)
        parseAddColumnUniqueConstraints(doc, filePath, results)
    }

    /**
     * Pattern A: <createIndex tableName="..." indexName="..." unique="...">
     *                <column name="..."/>
     *            </createIndex>
     */
    private fun parseCreateIndex(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "createIndex") { elem ->
            val tableName = elem.getAttribute("tableName")
            val indexName = elem.getAttribute("indexName")
            val isUnique = elem.getAttribute("unique") == "true"

            elem.childElements("column").forEachIndexed { position, colElem ->
                val columnName = colElem.getAttribute("name")
                if (tableName.isNotBlank() && columnName.isNotBlank()) {
                    results += IndexedColumn(
                        tableName = tableName,
                        columnName = columnName,
                        indexName = indexName,
                        filePath = filePath,
                        isUnique = isUnique,
                        compositePosition = position
                    )
                }
            }
        }
    }

    /**
     * Pattern B: <addUniqueConstraint tableName="..." columnNames="col1, col2" constraintName="..."/>
     */
    private fun parseAddUniqueConstraint(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "addUniqueConstraint") { elem ->
            val tableName = elem.getAttribute("tableName")
            val constraintName = elem.getAttribute("constraintName").ifBlank {
                elem.getAttribute("columnNames").replace(", ", "_") + "_uq"
            }

            elem.getAttribute("columnNames")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEachIndexed { position, colName ->
                    results += IndexedColumn(
                        tableName = tableName,
                        columnName = colName,
                        indexName = constraintName,
                        filePath = filePath,
                        isUnique = true,
                        compositePosition = position
                    )
                }
        }
    }

    /**
     * Patterns C & D: Raw SQL CREATE INDEX in <sql> elements.
     * Handles CONCURRENTLY, IF NOT EXISTS, ASC/DESC, and excludes INCLUDE clause columns.
     */
    private fun parseSqlElements(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "sql") { elem ->
            if (elem.isInsideRollback()) return@forEachElement

            val sqlText = elem.textContent ?: return@forEachElement

            createIndexSqlRegex.findAll(sqlText).forEach { match ->
                val isUnique = match.groupValues[1].isNotBlank()
                val indexName = match.groupValues[2]
                val tableName = match.groupValues[3]
                val columnsRaw = match.groupValues[4]

                val columns = columnsRaw.split(",").map { col ->
                    col.trim()
                        .replace(Regex("""\s+(ASC|DESC)\s*$""", RegexOption.IGNORE_CASE), "")
                        .trim()
                }.filter { it.isNotBlank() }

                columns.forEachIndexed { position, colName ->
                    // Check if the whole SQL after the matched CREATE INDEX has a WHERE clause
                    val fullRemaining = sqlText.substring(match.range.last + 1)
                    val nextStatementStart = fullRemaining.indexOf(';')
                    val statementRemainder = if (nextStatementStart >= 0) {
                        fullRemaining.substring(0, nextStatementStart)
                    } else {
                        fullRemaining
                    }
                    val isPartial = statementRemainder.contains(Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE))

                    results += IndexedColumn(
                        tableName = tableName,
                        columnName = colName,
                        indexName = indexName,
                        filePath = filePath,
                        isUnique = isUnique,
                        isPartial = isPartial,
                        compositePosition = position
                    )
                }
            }
        }
    }

    /**
     * Pattern E: <createTable> with inline primaryKey constraint.
     */
    private fun parsePrimaryKeys(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "createTable") { tableElem ->
            val tableName = tableElem.getAttribute("tableName")

            tableElem.childElements("column").forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                colElem.childElements("constraints")
                    .filter { it.getAttribute("primaryKey") == "true" }
                    .forEach { constraintElem ->
                        val pkName = constraintElem.getAttribute("primaryKeyName").ifBlank { "${tableName}_pkey" }
                        results += IndexedColumn(
                            tableName = tableName,
                            columnName = columnName,
                            indexName = pkName,
                            filePath = filePath,
                            isUnique = true,
                            compositePosition = 0
                        )
                    }
            }
        }
    }

    /**
     * Pattern G: <createTable> columns with <constraints unique="true"/>
     */
    private fun parseCreateTableUniqueConstraints(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "createTable") { tableElem ->
            val tableName = tableElem.getAttribute("tableName")

            tableElem.childElements("column").forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                colElem.childElements("constraints")
                    .filter { it.getAttribute("unique") == "true" && it.getAttribute("primaryKey") != "true" }
                    .forEach { constraintElem ->
                        val constraintName = constraintElem.getAttribute("uniqueConstraintName").ifBlank {
                            "${tableName}_${columnName}_unique"
                        }
                        results += IndexedColumn(
                            tableName = tableName,
                            columnName = columnName,
                            indexName = constraintName,
                            filePath = filePath,
                            isUnique = true,
                            compositePosition = 0
                        )
                    }
            }
        }
    }

    /**
     * Pattern F: <addColumn tableName="..."> with <constraints unique="true"/>
     */
    private fun parseAddColumnUniqueConstraints(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "addColumn") { addColElem ->
            val tableName = addColElem.getAttribute("tableName")

            addColElem.childElements("column").forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                colElem.childElements("constraints")
                    .filter { it.getAttribute("unique") == "true" }
                    .forEach { constraintElem ->
                        val constraintName = constraintElem.getAttribute("uniqueConstraintName").ifBlank {
                            "${tableName}_${columnName}_unique"
                        }
                        results += IndexedColumn(
                            tableName = tableName,
                            columnName = columnName,
                            indexName = constraintName,
                            filePath = filePath,
                            isUnique = true,
                            compositePosition = 0
                        )
                    }
            }
        }
    }

    // --- Utility functions ---

    private fun Element.isInsideRollback(): Boolean =
        generateSequence(parentNode) { it.parentNode }
            .any { it is Element && it.localName == "rollback" }

    private fun Element.childElements(localName: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.localName == localName || it.tagName == localName }

    private fun forEachElement(doc: Document, localName: String, action: (Element) -> Unit) {
        // Try namespace-aware lookup first
        val nsElements = doc.getElementsByTagNameNS("*", localName)
        val elements = if (nsElements.length > 0) nsElements else doc.getElementsByTagName(localName)
        elements.forEach(action)
    }

    private fun NodeList.forEach(action: (Element) -> Unit) {
        for (i in 0 until length) {
            (item(i) as? Element)?.let(action)
        }
    }
}
