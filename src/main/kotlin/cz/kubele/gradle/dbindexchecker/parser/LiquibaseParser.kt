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

    private val dbFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
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

        val results = mutableListOf<IndexedColumn>()
        val visited = mutableSetOf<String>()
        parseChangelogFile(changelogFile, results, visited)
        return results
    }

    private fun parseChangelogFile(
        file: File,
        results: MutableList<IndexedColumn>,
        visited: MutableSet<String>
    ) {
        val canonicalPath = file.canonicalPath
        if (canonicalPath in visited) return
        visited.add(canonicalPath)

        if (!file.exists()) return

        val doc = try {
            val builder = dbFactory.newDocumentBuilder()
            builder.parse(file)
        } catch (_: Exception) {
            return
        }

        val relativePath = file.name
        val parentDir = file.parentFile

        // Follow <include> references
        forEachElement(doc, "include") { includeElem ->
            val includedFilePath = includeElem.getAttribute("file")
            if (includedFilePath.isNotBlank()) {
                val relativeToChangelog = includeElem.getAttribute("relativeToChangelogFile")
                val resolvedFile = if (relativeToChangelog == "true") {
                    parentDir.resolve(includedFilePath)
                } else {
                    parentDir.resolve(includedFilePath)
                }
                parseChangelogFile(resolvedFile, results, visited)
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

            val columns = getChildElements(elem, "column")
            columns.forEachIndexed { position, colElem ->
                val columnName = colElem.getAttribute("name")
                if (tableName.isNotBlank() && columnName.isNotBlank()) {
                    results.add(
                        IndexedColumn(
                            tableName = tableName,
                            columnName = columnName,
                            indexName = indexName,
                            filePath = filePath,
                            isUnique = isUnique,
                            compositePosition = position
                        )
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
            val columnNames = elem.getAttribute("columnNames")

            columnNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .forEachIndexed { position, colName ->
                    results.add(
                        IndexedColumn(
                            tableName = tableName,
                            columnName = colName,
                            indexName = constraintName,
                            filePath = filePath,
                            isUnique = true,
                            compositePosition = position
                        )
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
            // Skip <sql> elements inside <rollback>
            if (isInsideRollback(elem)) return@forEachElement

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
                    val partial = sqlText.substringAfter(match.value).trimStart().let {
                        it.startsWith("WHERE", ignoreCase = true) ||
                                it.startsWith("INCLUDE", ignoreCase = true) &&
                                sqlText.contains(Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE))
                    }

                    // Check if the whole SQL after the matched CREATE INDEX has a WHERE clause
                    val fullRemaining = sqlText.substring(match.range.last + 1)
                    val nextStatementStart = fullRemaining.indexOf(';')
                    val statementRemainder = if (nextStatementStart >= 0) {
                        fullRemaining.substring(0, nextStatementStart)
                    } else {
                        fullRemaining
                    }
                    val isPartial = statementRemainder.contains(Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE))

                    results.add(
                        IndexedColumn(
                            tableName = tableName,
                            columnName = colName,
                            indexName = indexName,
                            filePath = filePath,
                            isUnique = isUnique,
                            isPartial = isPartial,
                            compositePosition = position
                        )
                    )
                }
            }
        }
    }

    /**
     * Pattern E: <createTable> with inline primaryKey constraint, or
     * <constraints primaryKey="true" primaryKeyName="..."/>
     */
    private fun parsePrimaryKeys(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "createTable") { tableElem ->
            val tableName = tableElem.getAttribute("tableName")
            val columns = getChildElements(tableElem, "column")

            columns.forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                val constraints = getChildElements(colElem, "constraints")
                constraints.forEach { constraintElem ->
                    if (constraintElem.getAttribute("primaryKey") == "true") {
                        val pkName = constraintElem.getAttribute("primaryKeyName").ifBlank {
                            "${tableName}_pkey"
                        }
                        results.add(
                            IndexedColumn(
                                tableName = tableName,
                                columnName = columnName,
                                indexName = pkName,
                                filePath = filePath,
                                isUnique = true,
                                compositePosition = 0
                            )
                        )
                    }
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
            val columns = getChildElements(tableElem, "column")

            columns.forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                val constraints = getChildElements(colElem, "constraints")
                constraints.forEach { constraintElem ->
                    if (constraintElem.getAttribute("unique") == "true" &&
                        constraintElem.getAttribute("primaryKey") != "true"
                    ) {
                        val constraintName = constraintElem.getAttribute("uniqueConstraintName").ifBlank {
                            "${tableName}_${columnName}_unique"
                        }
                        results.add(
                            IndexedColumn(
                                tableName = tableName,
                                columnName = columnName,
                                indexName = constraintName,
                                filePath = filePath,
                                isUnique = true,
                                compositePosition = 0
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Pattern F: <addColumn tableName="...">
     *                <column name="..." type="...">
     *                    <constraints unique="true"/>
     *                </column>
     *            </addColumn>
     */
    private fun parseAddColumnUniqueConstraints(doc: Document, filePath: String, results: MutableList<IndexedColumn>) {
        forEachElement(doc, "addColumn") { addColElem ->
            val tableName = addColElem.getAttribute("tableName")
            val columns = getChildElements(addColElem, "column")

            columns.forEach { colElem ->
                val columnName = colElem.getAttribute("name")
                val constraints = getChildElements(colElem, "constraints")
                constraints.forEach { constraintElem ->
                    if (constraintElem.getAttribute("unique") == "true") {
                        val constraintName = constraintElem.getAttribute("uniqueConstraintName").ifBlank {
                            "${tableName}_${columnName}_unique"
                        }
                        results.add(
                            IndexedColumn(
                                tableName = tableName,
                                columnName = columnName,
                                indexName = constraintName,
                                filePath = filePath,
                                isUnique = true,
                                compositePosition = 0
                            )
                        )
                    }
                }
            }
        }
    }

    // --- Utility functions ---

    private fun isInsideRollback(element: Element): Boolean {
        var parent = element.parentNode
        while (parent != null) {
            if (parent is Element && parent.localName == "rollback") return true
            parent = parent.parentNode
        }
        return false
    }

    /**
     * Iterates over all elements with the given local name in the document,
     * handling XML namespace-aware lookups.
     */
    private fun forEachElement(doc: Document, localName: String, action: (Element) -> Unit) {
        // Try namespace-aware lookup first
        val nsElements = doc.getElementsByTagNameNS("*", localName)
        if (nsElements.length > 0) {
            forEachNode(nsElements, action)
            return
        }
        // Fallback to non-namespace lookup
        val elements = doc.getElementsByTagName(localName)
        forEachNode(elements, action)
    }

    private fun forEachNode(nodeList: NodeList, action: (Element) -> Unit) {
        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            if (node is Element) {
                action(node)
            }
        }
    }

    private fun getChildElements(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && (child.localName == localName || child.tagName == localName)) {
                result.add(child)
            }
        }
        return result
    }
}
