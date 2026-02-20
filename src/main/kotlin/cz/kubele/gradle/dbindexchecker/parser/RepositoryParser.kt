package cz.kubele.gradle.dbindexchecker.parser

import cz.kubele.gradle.dbindexchecker.model.QueryColumn
import cz.kubele.gradle.dbindexchecker.model.QueryType
import cz.kubele.gradle.dbindexchecker.model.TableMapping
import java.io.File
import kotlin.text.iterator

/**
 * Parses Spring Data repository Kotlin files to extract columns used in queries.
 * Handles derived query methods, @Query JPQL, and @Query native SQL.
 */
object RepositoryParser {

    // Prefixes for Spring Data derived query methods
    private val DERIVED_QUERY_PREFIXES = listOf(
        "findAllBy", "findFirstBy", "findTopBy", "findBy",
        "existsBy",
        "countAllBy", "countBy",
        "deleteAllBy", "deleteBy",
        "streamAllBy", "streamBy",
        "removeAllBy", "removeBy"
    )

    // Suffix keywords that modify conditions but are not field names.
    // Order matters: longer/more-specific keywords first to avoid partial matches.
    private val CONDITION_SUFFIXES = listOf(
        "ContainsIgnoreCase",
        "Containing",
        "Contains",
        "StartingWith",
        "EndingWith",
        "LessThanEqual",
        "GreaterThanEqual",
        "LessThan",
        "GreaterThan",
        "IsNotNull",
        "NotNull",
        "IsNull",
        "Null",
        "NotLike",
        "Like",
        "IgnoreCase",
        "Between",
        "After",
        "Before",
        "NotIn",
        "In",
        "IsTrue",
        "IsFalse",
        "True",
        "False",
        "IsNot",
        "Is",
        "Not",
        "Equals"
    )

    // Regex to detect repository interface declarations and extract entity type.
    // Matches patterns like: CrudRepository<SomeEntity, Long>
    private val REPO_ENTITY_REGEX = Regex(
        """(?:CrudRepository|JpaRepository|PagingAndSortingRepository)<\s*(\w+)\s*,"""
    )

    // Regex to detect a fun declaration (interface method)
    private val FUN_DECLARATION_REGEX = Regex("""^\s*(?:(?:override|suspend)\s+)*fun\s+(\w+)\s*\(""")

    // Regex to detect @Query annotation start
    private val QUERY_ANNOTATION_START = Regex("""@Query\s*\(""")

    // Regex to detect nativeQuery = true
    private val NATIVE_QUERY_REGEX = Regex("""nativeQuery\s*=\s*true""")

    fun parseRepositories(daoDir: File, entityMappings: Map<String, TableMapping>): List<QueryColumn> {
        if (!daoDir.exists()) return emptyList()

        val results = mutableListOf<QueryColumn>()

        daoDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                results.addAll(parseRepositoryFile(file, entityMappings))
            }

        return results
    }

    private fun parseRepositoryFile(file: File, entityMappings: Map<String, TableMapping>): List<QueryColumn> {
        val content = file.readText()
        val lines = content.lines()
        val filePath = file.absolutePath

        // Detect entity type from repository generic parameter
        val entityName = REPO_ENTITY_REGEX.find(content)?.groupValues?.get(1) ?: return emptyList()
        val tableMapping = entityMappings[entityName] ?: return emptyList()

        val results = mutableListOf<QueryColumn>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Check for @Query annotation
            if (QUERY_ANNOTATION_START.containsMatchIn(line)) {
                val queryResult = extractQueryAnnotation(lines, i)
                if (queryResult != null) {
                    val (queryText, isNative, queryEndLine) = queryResult
                    // Find the fun declaration after the query annotation
                    val funLine = findFunAfterLine(lines, queryEndLine)
                    val funName = if (funLine != null) {
                        FUN_DECLARATION_REGEX.find(lines[funLine])?.groupValues?.get(1) ?: "unknown"
                    } else {
                        "unknown"
                    }
                    val reportLine = i + 1 // 1-based line number

                    if (isNative) {
                        results.addAll(
                            parseNativeSqlQuery(queryText, tableMapping, funName, filePath, reportLine)
                        )
                    } else {
                        results.addAll(
                            parseJpqlQuery(queryText, entityName, tableMapping, funName, filePath, reportLine)
                        )
                    }
                    i = queryResult.endLineIndex + 1
                    continue
                }
            }

            // Check for derived query method (fun without @Query)
            val funMatch = FUN_DECLARATION_REGEX.find(line)
            if (funMatch != null) {
                val methodName = funMatch.groupValues[1]
                // Only process if NOT preceded by @Query (look back a few lines)
                if (!isPrecededByQueryAnnotation(lines, i)) {
                    results.addAll(
                        parseDerivedQuery(methodName, tableMapping, filePath, i + 1)
                    )
                }
            }

            i++
        }

        return results
    }

    // ========== Derived Query Parsing ==========

    private fun parseDerivedQuery(
        methodName: String,
        tableMapping: TableMapping,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        // Try to match a derived query prefix
        var afterPrefix: String? = null
        for (prefix in DERIVED_QUERY_PREFIXES) {
            if (methodName.startsWith(prefix) && methodName.length > prefix.length) {
                afterPrefix = methodName.substring(prefix.length)
                break
            }
        }
        if (afterPrefix == null) return emptyList()

        val results = mutableListOf<QueryColumn>()

        // Split off OrderBy clause
        val orderByIndex = afterPrefix.indexOf("OrderBy")
        val wherePart: String
        val orderByPart: String?
        if (orderByIndex >= 0) {
            wherePart = afterPrefix.substring(0, orderByIndex)
            orderByPart = afterPrefix.substring(orderByIndex + "OrderBy".length)
        } else {
            wherePart = afterPrefix
            orderByPart = null
        }

        // Parse WHERE fields: split by And/Or (only at uppercase boundaries)
        if (wherePart.isNotEmpty()) {
            val fieldTokens = splitByConditionSeparators(wherePart)
            for (token in fieldTokens) {
                val fieldName = stripConditionSuffixes(token)
                if (fieldName.isNotEmpty()) {
                    val columnName = resolveFieldToColumn(fieldName, tableMapping)
                    if (columnName != null) {
                        results.add(
                            QueryColumn(
                                tableName = tableMapping.tableName,
                                columnName = columnName,
                                source = "derived query: $methodName",
                                filePath = filePath,
                                lineNumber = lineNumber,
                                queryType = QueryType.DERIVED_QUERY
                            )
                        )
                    }
                }
            }
        }

        // Parse ORDER BY fields
        if (orderByPart != null && orderByPart.isNotEmpty()) {
            val orderField = orderByPart
                .removeSuffix("Asc")
                .removeSuffix("Desc")
            if (orderField.isNotEmpty()) {
                val columnName = resolveFieldToColumn(orderField, tableMapping)
                if (columnName != null) {
                    results.add(
                        QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = columnName,
                            source = "derived query ORDER BY: $methodName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.DERIVED_QUERY
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Splits a derived query predicate part by And/Or separators.
     * E.g., "UserDataIdAndActionButtonClickedAtIsNullAndCanceledAtIsNull"
     *    -> ["UserDataId", "ActionButtonClickedAtIsNull", "CanceledAtIsNull"]
     *
     * We need to be careful: "And" and "Or" only act as separators when followed by
     * an uppercase letter (to avoid splitting "Random" on "And").
     */
    private fun splitByConditionSeparators(predicate: String): List<String> {
        // Split on And or Or that are followed by an uppercase letter
        val parts = mutableListOf<String>()
        val pattern = Regex("""(?:And|Or)(?=[A-Z])""")
        var remaining = predicate
        while (remaining.isNotEmpty()) {
            val match = pattern.find(remaining)
            if (match != null) {
                val before = remaining.substring(0, match.range.first)
                if (before.isNotEmpty()) {
                    parts.add(before)
                }
                remaining = remaining.substring(match.range.last + 1)
            } else {
                parts.add(remaining)
                break
            }
        }
        return parts
    }

    /**
     * Strips condition suffix keywords from a field token.
     * E.g., "ReceiptCreationTimeBetween" -> "ReceiptCreationTime"
     *       "ActionButtonClickedAtIsNotNull" -> "ActionButtonClickedAt"
     *       "DraftIsFalse" -> "Draft"
     */
    private fun stripConditionSuffixes(token: String): String {
        var result = token
        // Try stripping suffixes - try longest first (list is already ordered)
        for (suffix in CONDITION_SUFFIXES) {
            if (result.endsWith(suffix) && result.length > suffix.length) {
                result = result.substring(0, result.length - suffix.length)
                // After stripping one suffix, try again (e.g., "ContainsIgnoreCase")
                break
            }
        }
        return result
    }

    /**
     * Resolves a field name (from derived query, with first letter uppercase) to a column name.
     * First tries exact match (with lowercase first letter) in fieldToColumn map,
     * then falls back to camelToSnake conversion.
     */
    private fun resolveFieldToColumn(fieldName: String, tableMapping: TableMapping): String? {
        // Derived query field names have uppercase first letter; entity fields have lowercase
        val entityFieldName = fieldName.replaceFirstChar { it.lowercase() }

        // Look up in the entity's field-to-column mapping
        val column = tableMapping.fieldToColumn[entityFieldName]
        if (column != null) return column

        // Fallback: convert to snake_case
        return EntityParser.camelToSnake(entityFieldName)
    }

    // ========== @Query Annotation Extraction ==========

    private data class QueryAnnotationResult(
        val queryText: String,
        val isNative: Boolean,
        val endLineIndex: Int
    )

    /**
     * Extracts the full @Query annotation content starting from the line where @Query is found.
     * Handles single-line strings, multi-line concatenation with +, and triple-quoted strings.
     * Returns the query text, whether it's native, and the line index where the annotation ends.
     */
    private fun extractQueryAnnotation(lines: List<String>, startLine: Int): QueryAnnotationResult? {
        // Collect all lines that form the @Query annotation (until the closing parenthesis)
        val annotationBuilder = StringBuilder()
        var parenDepth = 0
        var endLine = startLine
        var foundStart = false

        for (idx in startLine until lines.size) {
            val line = lines[idx]
            for (ch in line) {
                if (ch == '(') {
                    parenDepth++
                    foundStart = true
                } else if (ch == ')' && foundStart) {
                    parenDepth--
                    if (parenDepth == 0) {
                        annotationBuilder.append(line)
                        endLine = idx
                        // We've found the full annotation
                        val fullAnnotation = annotationBuilder.toString()
                        val isNative = NATIVE_QUERY_REGEX.containsMatchIn(fullAnnotation)
                        val queryText = extractQueryString(fullAnnotation)
                        return if (queryText != null) {
                            QueryAnnotationResult(queryText, isNative, endLine)
                        } else {
                            null
                        }
                    }
                }
            }
            annotationBuilder.append(line).append(" ")
        }
        return null
    }

    /**
     * Extracts the query string from the @Query annotation text.
     * Handles:
     * - @Query("some query")
     * - @Query(value = "some query")
     * - @Query("part1" + "part2")
     * - @Query(\"\"\"some query\"\"\")
     * - @Query(value = \"\"\"some query\"\"\")
     */
    private fun extractQueryString(annotationText: String): String? {
        // Try triple-quoted strings first
        val tripleQuoteRegex = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val tripleMatches = tripleQuoteRegex.findAll(annotationText).toList()
        if (tripleMatches.isNotEmpty()) {
            // Concatenate all triple-quoted parts
            return tripleMatches.joinToString(" ") { it.groupValues[1].trim() }
        }

        // Try regular double-quoted strings (possibly concatenated with +)
        val doubleQuoteRegex = Regex("\"([^\"]*)\"")
        val doubleMatches = doubleQuoteRegex.findAll(annotationText).toList()
        if (doubleMatches.isNotEmpty()) {
            // Filter out values that are clearly not query parts (like "true", parameter names)
            val queryParts = doubleMatches
                .map { it.groupValues[1] }
                .filter { part ->
                    // Skip non-query values
                    part != "true" && part != "false" && part.length > 1
                }
            if (queryParts.isNotEmpty()) {
                return queryParts.joinToString(" ")
            }
        }

        return null
    }

    // ========== JPQL Query Parsing ==========

    private fun parseJpqlQuery(
        query: String,
        entityName: String,
        tableMapping: TableMapping,
        funName: String,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        val results = mutableListOf<QueryColumn>()
        val normalized = query.replace(Regex("\\s+"), " ").trim()

        // Extract entity alias from FROM clause
        // Patterns: "FROM EntityName e", "from EntityName e", "SELECT ... FROM EntityName e"
        val aliasRegex = Regex(
            """(?:FROM|from|JOIN|join)\s+\w+\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        val aliases = mutableSetOf<String>()
        aliasRegex.findAll(normalized).forEach { match ->
            val alias = match.groupValues[1]
            // Filter out SQL keywords that might falsely match
            if (alias.lowercase() !in setOf("where", "on", "and", "or", "set", "join", "left", "right", "inner", "outer", "order", "group", "having", "limit", "as", "in", "not", "null", "is", "between", "like", "true", "false", "select", "from", "into", "update", "delete")) {
                aliases.add(alias)
            }
        }

        if (aliases.isEmpty()) return results

        // Find alias.field references in WHERE, ON, ORDER BY, and general conditions
        for (alias in aliases) {
            val fieldRefRegex = Regex("""(?<!\w)${Regex.escape(alias)}\.(\w+)""")
            fieldRefRegex.findAll(normalized).forEach { match ->
                val fieldName = match.groupValues[1]
                // Skip nested path references (e.g., alias.field.subfield - only take top-level)
                // Also skip common non-column references
                if (fieldName.lowercase() !in setOf("class", "size")) {
                    val columnName = tableMapping.fieldToColumn[fieldName]
                        ?: EntityParser.camelToSnake(fieldName)
                    results.add(
                        QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = columnName,
                            source = "JPQL @Query: $funName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.JPQL
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.columnName }
    }

    // ========== Native SQL Query Parsing ==========

    private fun parseNativeSqlQuery(
        query: String,
        tableMapping: TableMapping,
        funName: String,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        val results = mutableListOf<QueryColumn>()
        val normalized = query.replace(Regex("\\s+"), " ").trim()

        // Build alias -> table map from FROM and JOIN clauses
        val aliasToTable = mutableMapOf<String, String>()
        val tableAliasPattern = Regex(
            """(?:FROM|JOIN)\s+(\w+)\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        tableAliasPattern.findAll(normalized).forEach { match ->
            val table = match.groupValues[1].lowercase()
            val alias = match.groupValues[2].lowercase()
            if (alias !in setOf("on", "where", "inner", "left", "right", "outer", "cross", "natural", "join", "set")) {
                aliasToTable[alias] = table
            }
        }
        val entityTable = tableMapping.tableName.lowercase()
        val entityAliases = aliasToTable.filterValues { it == entityTable }.keys
        val isMultiTable = aliasToTable.size > 1

        // Helper: check if a qualified column reference belongs to the entity table
        fun isEntityColumn(alias: String?, column: String): Boolean {
            if (alias != null) return alias.lowercase() in entityAliases
            // Unqualified column: only report in single-table queries
            return !isMultiTable
        }

        // Extract column references from WHERE clauses
        val whereIndex = normalized.indexOfFirst("WHERE", "where")
        if (whereIndex < 0) return results

        val afterWhere = normalized.substring(whereIndex)

        // Match column names that appear in conditions, capturing optional alias
        val columnPattern = Regex(
            """(?:WHERE|AND|OR)\s+(?:(\w+)\.)?(\w+)\s*(?:=|<>|!=|<=|>=|<|>|IS\s|IN\s|IN\(|BETWEEN\s|LIKE\s|NOT\s)""",
            RegexOption.IGNORE_CASE
        )
        columnPattern.findAll(afterWhere).forEach { match ->
            val alias = match.groupValues[1].ifEmpty { null }
            val columnName = match.groupValues[2].lowercase()
            if (columnName !in setOf("null", "not", "true", "false", "and", "or", "exists", "select", "case", "when", "then", "else", "end")
                && isEntityColumn(alias, columnName)) {
                results.add(
                    QueryColumn(
                        tableName = tableMapping.tableName,
                        columnName = columnName,
                        source = "native @Query: $funName",
                        filePath = filePath,
                        lineNumber = lineNumber,
                        queryType = QueryType.NATIVE_SQL
                    )
                )
            }
        }

        // Also extract columns from JOIN ON conditions, capturing aliases
        val joinOnPattern = Regex(
            """(?:ON)\s+(?:(\w+)\.)?(\w+)\s*=\s*(?:(\w+)\.)?(\w+)""",
            RegexOption.IGNORE_CASE
        )
        joinOnPattern.findAll(normalized).forEach { match ->
            val alias1 = match.groupValues[1].ifEmpty { null }
            val col1 = match.groupValues[2].lowercase()
            val alias2 = match.groupValues[3].ifEmpty { null }
            val col2 = match.groupValues[4].lowercase()
            for ((alias, col) in listOf(alias1 to col1, alias2 to col2)) {
                if (col !in setOf("id", "null", "true", "false") && isEntityColumn(alias, col)) {
                    results.add(
                        QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = col,
                            source = "native @Query JOIN: $funName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.NATIVE_SQL
                        )
                    )
                }
            }
        }

        // Extract ORDER BY columns
        val orderByRegex = Regex("""ORDER\s+BY\s+([\w.,\s]+?)(?:\s+LIMIT|\s*$)""", RegexOption.IGNORE_CASE)
        val orderByMatch = orderByRegex.find(normalized)
        if (orderByMatch != null) {
            val orderCols = orderByMatch.groupValues[1]
            val colNameRegex = Regex("""(?:(\w+)\.)?(\w+)\s*(?:ASC|DESC|,)?""", RegexOption.IGNORE_CASE)
            colNameRegex.findAll(orderCols).forEach { match ->
                val alias = match.groupValues[1].ifEmpty { null }
                val col = match.groupValues[2].lowercase()
                if (col !in setOf("asc", "desc", "nulls", "first", "last") && isEntityColumn(alias, col)) {
                    results.add(
                        QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = col,
                            source = "native @Query ORDER BY: $funName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.NATIVE_SQL
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.columnName }
    }

    // ========== Utility ==========

    /**
     * Checks whether a fun declaration at the given line is preceded by @Query annotation
     * (within the last several lines).
     */
    private fun isPrecededByQueryAnnotation(lines: List<String>, funLineIndex: Int): Boolean {
        // Look back up to 30 lines for @Query
        for (offset in 1..30) {
            val idx = funLineIndex - offset
            if (idx < 0) break
            val line = lines[idx].trim()
            if (QUERY_ANNOTATION_START.containsMatchIn(line)) return true
            // If we hit another fun declaration, stop looking
            if (FUN_DECLARATION_REGEX.containsMatchIn(line)) break
            // If we hit interface declaration, stop
            if (line.startsWith("interface ")) break
        }
        return false
    }

    /**
     * Finds the next fun declaration after a given line index.
     */
    private fun findFunAfterLine(lines: List<String>, startLine: Int): Int? {
        for (idx in (startLine + 1) until minOf(lines.size, startLine + 10)) {
            if (FUN_DECLARATION_REGEX.containsMatchIn(lines[idx])) {
                return idx
            }
        }
        return null
    }

    /**
     * Case-insensitive indexOf for multiple needles.
     */
    private fun String.indexOfFirst(vararg needles: String): Int {
        var minIndex = -1
        for (needle in needles) {
            val idx = this.indexOf(needle)
            if (idx >= 0 && (minIndex < 0 || idx < minIndex)) {
                minIndex = idx
            }
        }
        return minIndex
    }
}
