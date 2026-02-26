package ee.kubele.gradle.dbindexchecker.parser

import ee.kubele.gradle.dbindexchecker.model.QueryColumn
import ee.kubele.gradle.dbindexchecker.model.QueryType
import ee.kubele.gradle.dbindexchecker.model.TableMapping
import java.io.File

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
    private val REPO_ENTITY_REGEX = Regex(
        """(?:CrudRepository|JpaRepository|PagingAndSortingRepository)<\s*(\w+)\s*,"""
    )

    // Regex to detect @SuppressIndexCheck in a comment
    private val SUPPRESS_INDEX_CHECK_REGEX = Regex("""@SuppressIndexCheck(?:\s*\(\s*(.*?)\s*\))?""")

    // Regex to detect a fun declaration (interface method)
    private val FUN_DECLARATION_REGEX = Regex("""^\s*(?:(?:override|suspend)\s+)*fun\s+(\w+)\s*\(""")

    // Regex to detect @Query annotation start
    private val QUERY_ANNOTATION_START = Regex("""@Query\s*\(""")

    // Regex to detect nativeQuery = true
    private val NATIVE_QUERY_REGEX = Regex("""nativeQuery\s*=\s*true""")

    fun parseRepositories(daoDir: File, entityMappings: Map<String, TableMapping>): List<QueryColumn> {
        if (!daoDir.exists()) return emptyList()

        return daoDir.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { parseRepositoryFile(it, entityMappings) }
            .toList()
    }

    private fun parseRepositoryFile(file: File, entityMappings: Map<String, TableMapping>): List<QueryColumn> {
        val content = file.readText()
        val lines = content.lines()
        val filePath = file.absolutePath

        val entityName = REPO_ENTITY_REGEX.find(content)?.groupValues?.get(1) ?: return emptyList()
        val tableMapping = entityMappings[entityName] ?: return emptyList()

        return buildList {
            // Tracks @SuppressIndexCheck state: null = no suppression, empty = suppress all, non-empty = suppress specific columns
            var pendingSuppression: Set<String>? = null

            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                // Check for @SuppressIndexCheck comment
                val suppressMatch = SUPPRESS_INDEX_CHECK_REGEX.find(line)
                if (suppressMatch != null && line.trimStart().startsWith("//")) {
                    val columnsArg = suppressMatch.groupValues[1].trim()
                    pendingSuppression = if (columnsArg.isEmpty()) {
                        emptySet()
                    } else {
                        Regex(""""([^"]+)"""").findAll(columnsArg)
                            .map { it.groupValues[1].lowercase() }
                            .toSet()
                    }
                    i++
                    continue
                }

                // Check for @Query annotation
                if (QUERY_ANNOTATION_START.containsMatchIn(line)) {
                    val queryResult = extractQueryAnnotation(lines, i)
                    if (queryResult != null) {
                        val (queryText, isNative, queryEndLine) = queryResult
                        val funName = findFunAfterLine(lines, queryEndLine)
                            ?.let { FUN_DECLARATION_REGEX.find(lines[it])?.groupValues?.get(1) }
                            ?: "unknown"
                        val reportLine = i + 1

                        val queryColumns = if (isNative) {
                            parseNativeSqlQuery(queryText, tableMapping, funName, filePath, reportLine)
                        } else {
                            parseJpqlQuery(queryText, tableMapping, funName, filePath, reportLine)
                        }
                        addAll(applySuppression(queryColumns, pendingSuppression))
                        pendingSuppression = null
                        i = queryResult.endLineIndex + 1
                        continue
                    }
                }

                // Check for derived query method (fun without @Query)
                FUN_DECLARATION_REGEX.find(line)?.let { funMatch ->
                    val methodName = funMatch.groupValues[1]
                    if (!isPrecededByQueryAnnotation(lines, i)) {
                        addAll(applySuppression(parseDerivedQuery(methodName, tableMapping, filePath, i + 1), pendingSuppression))
                    }
                    pendingSuppression = null
                }

                i++
            }
        }
    }

    /**
     * Applies @SuppressIndexCheck filtering to a list of query columns.
     * If suppression is null, returns the list unchanged.
     * If suppression is empty, suppresses all columns.
     * If suppression has specific columns, only those are removed.
     */
    private fun applySuppression(columns: List<QueryColumn>, suppression: Set<String>?): List<QueryColumn> = when {
        suppression == null -> columns
        suppression.isEmpty() -> emptyList()
        else -> columns.filter { it.columnName.lowercase() !in suppression }
    }

    // ========== Derived Query Parsing ==========

    private fun parseDerivedQuery(
        methodName: String,
        tableMapping: TableMapping,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        val afterPrefix = DERIVED_QUERY_PREFIXES
            .firstOrNull { methodName.startsWith(it) && methodName.length > it.length }
            ?.let { methodName.substring(it.length) }
            ?: return emptyList()

        val orderByIndex = afterPrefix.indexOf("OrderBy")
        val wherePart = if (orderByIndex >= 0) afterPrefix.substring(0, orderByIndex) else afterPrefix
        val orderByPart = if (orderByIndex >= 0) afterPrefix.substring(orderByIndex + "OrderBy".length) else null

        return buildList {
            // Parse WHERE fields: split by And/Or (only at uppercase boundaries)
            if (wherePart.isNotEmpty()) {
                splitByConditionSeparators(wherePart).forEach { token ->
                    val fieldName = stripConditionSuffixes(token)
                    if (fieldName.isNotEmpty()) {
                        add(QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = resolveFieldToColumn(fieldName, tableMapping),
                            source = "derived query: $methodName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.DERIVED_QUERY
                        ))
                    }
                }
            }

            // Parse ORDER BY fields
            if (!orderByPart.isNullOrEmpty()) {
                val orderField = orderByPart.removeSuffix("Asc").removeSuffix("Desc")
                if (orderField.isNotEmpty()) {
                    add(QueryColumn(
                        tableName = tableMapping.tableName,
                        columnName = resolveFieldToColumn(orderField, tableMapping),
                        source = "derived query ORDER BY: $methodName",
                        filePath = filePath,
                        lineNumber = lineNumber,
                        queryType = QueryType.DERIVED_QUERY
                    ))
                }
            }
        }
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
        val pattern = Regex("""(?:And|Or)(?=[A-Z])""")
        val separators = pattern.findAll(predicate).toList()
        if (separators.isEmpty()) return listOf(predicate)

        return buildList {
            var lastEnd = 0
            for (match in separators) {
                val before = predicate.substring(lastEnd, match.range.first)
                if (before.isNotEmpty()) add(before)
                lastEnd = match.range.last + 1
            }
            val tail = predicate.substring(lastEnd)
            if (tail.isNotEmpty()) add(tail)
        }
    }

    /**
     * Strips condition suffix keywords from a field token.
     * E.g., "ReceiptCreationTimeBetween" -> "ReceiptCreationTime"
     *       "ActionButtonClickedAtIsNotNull" -> "ActionButtonClickedAt"
     *       "DraftIsFalse" -> "Draft"
     */
    private fun stripConditionSuffixes(token: String): String {
        // Try stripping suffixes - try longest first (list is already ordered)
        for (suffix in CONDITION_SUFFIXES) {
            if (token.endsWith(suffix) && token.length > suffix.length) {
                return token.removeSuffix(suffix)
            }
        }
        return token
    }

    /**
     * Resolves a field name (from derived query, with first letter uppercase) to a column name.
     * First tries exact match (with lowercase first letter) in fieldToColumn map,
     * then falls back to camelToSnake conversion.
     */
    private fun resolveFieldToColumn(fieldName: String, tableMapping: TableMapping): String {
        val entityFieldName = fieldName.replaceFirstChar { it.lowercase() }
        return tableMapping.fieldToColumn[entityFieldName] ?: EntityParser.camelToSnake(entityFieldName)
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
     */
    private fun extractQueryAnnotation(lines: List<String>, startLine: Int): QueryAnnotationResult? {
        val annotationBuilder = StringBuilder()
        var parenDepth = 0
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
                        val fullAnnotation = annotationBuilder.toString()
                        val isNative = NATIVE_QUERY_REGEX.containsMatchIn(fullAnnotation)
                        val queryText = extractQueryString(fullAnnotation) ?: return null
                        return QueryAnnotationResult(queryText, isNative, idx)
                    }
                }
            }
            annotationBuilder.append(line).append(" ")
        }
        return null
    }

    /**
     * Extracts the query string from the @Query annotation text.
     * Handles single/multi-line double-quoted and triple-quoted strings.
     */
    private fun extractQueryString(annotationText: String): String? {
        // Try triple-quoted strings first
        val tripleQuoteRegex = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        tripleQuoteRegex.findAll(annotationText).toList().takeIf { it.isNotEmpty() }?.let { matches ->
            return matches.joinToString(" ") { it.groupValues[1].trim() }
        }

        // Try regular double-quoted strings (possibly concatenated with +)
        val doubleQuoteRegex = Regex("\"([^\"]*)\"")
        return doubleQuoteRegex.findAll(annotationText)
            .map { it.groupValues[1] }
            .filter { it != "true" && it != "false" && it.length > 1 }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }

    // ========== JPQL Query Parsing ==========

    private val SQL_KEYWORDS = setOf(
        "where", "on", "and", "or", "set", "join", "left", "right", "inner", "outer",
        "order", "group", "having", "limit", "as", "in", "not", "null", "is", "between",
        "like", "true", "false", "select", "from", "into", "update", "delete"
    )

    private fun parseJpqlQuery(
        query: String,
        tableMapping: TableMapping,
        funName: String,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        val normalized = query.replace(Regex("\\s+"), " ").trim()

        val aliasRegex = Regex(
            """(?:FROM|from|JOIN|join)\s+\w+\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        val aliases = aliasRegex.findAll(normalized)
            .map { it.groupValues[1] }
            .filter { it.lowercase() !in SQL_KEYWORDS }
            .toSet()

        if (aliases.isEmpty()) return emptyList()

        // Find alias.field references in WHERE, ON, ORDER BY, and general conditions
        return aliases.flatMap { alias ->
            Regex("""(?<!\w)${Regex.escape(alias)}\.(\w+)""")
                .findAll(normalized)
                .map { it.groupValues[1] }
                .filter { it.lowercase() !in setOf("class", "size") }
                .map { fieldName ->
                    QueryColumn(
                        tableName = tableMapping.tableName,
                        columnName = tableMapping.fieldToColumn[fieldName] ?: EntityParser.camelToSnake(fieldName),
                        source = "JPQL @Query: $funName",
                        filePath = filePath,
                        lineNumber = lineNumber,
                        queryType = QueryType.JPQL
                    )
                }
                .toList()
        }.distinctBy { it.columnName }
    }

    // ========== Native SQL Query Parsing ==========

    private val JOIN_ALIAS_KEYWORDS = setOf("on", "where", "inner", "left", "right", "outer", "cross", "natural", "join", "set")
    private val SQL_VALUE_KEYWORDS = setOf("null", "not", "true", "false", "and", "or", "exists", "select", "case", "when", "then", "else", "end")
    private val ORDER_BY_KEYWORDS = setOf("asc", "desc", "nulls", "first", "last")

    private fun parseNativeSqlQuery(
        query: String,
        tableMapping: TableMapping,
        funName: String,
        filePath: String,
        lineNumber: Int
    ): List<QueryColumn> {
        val normalized = query.replace(Regex("\\s+"), " ").trim()

        // Build alias -> table map from FROM and JOIN clauses
        val aliasToTable = Regex("""(?:FROM|JOIN)\s+(\w+)\s+(\w+)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .filter { it.groupValues[2].lowercase() !in JOIN_ALIAS_KEYWORDS }
            .associate { it.groupValues[2].lowercase() to it.groupValues[1].lowercase() }

        val entityTable = tableMapping.tableName.lowercase()
        val entityAliases = aliasToTable.filterValues { it == entityTable }.keys
        val isMultiTable = aliasToTable.size > 1

        fun isEntityColumn(alias: String?): Boolean =
            if (alias != null) alias.lowercase() in entityAliases else !isMultiTable

        // Extract column references from WHERE clauses
        val whereIndex = normalized.indexOf("WHERE", ignoreCase = true)
        if (whereIndex < 0) return emptyList()

        val afterWhere = normalized.substring(whereIndex)

        return buildList {
            // Match column names that appear in conditions, capturing optional alias
            val columnPattern = Regex(
                """(?:WHERE|AND|OR)\s+(?:(\w+)\.)?(\w+)\s*(?:=|<>|!=|<=|>=|<|>|IS\s|IN\s|IN\(|BETWEEN\s|LIKE\s|NOT\s)""",
                RegexOption.IGNORE_CASE
            )
            columnPattern.findAll(afterWhere).forEach { match ->
                val alias = match.groupValues[1].ifEmpty { null }
                val columnName = match.groupValues[2].lowercase()
                if (columnName !in SQL_VALUE_KEYWORDS && isEntityColumn(alias)) {
                    add(QueryColumn(
                        tableName = tableMapping.tableName,
                        columnName = columnName,
                        source = "native @Query: $funName",
                        filePath = filePath,
                        lineNumber = lineNumber,
                        queryType = QueryType.NATIVE_SQL
                    ))
                }
            }

            // Also extract columns from JOIN ON conditions
            val joinOnPattern = Regex(
                """ON\s+(?:(\w+)\.)?(\w+)\s*=\s*(?:(\w+)\.)?(\w+)""",
                RegexOption.IGNORE_CASE
            )
            joinOnPattern.findAll(normalized).forEach { match ->
                val alias1 = match.groupValues[1].ifEmpty { null }
                val col1 = match.groupValues[2].lowercase()
                val alias2 = match.groupValues[3].ifEmpty { null }
                val col2 = match.groupValues[4].lowercase()
                for ((alias, col) in listOf(alias1 to col1, alias2 to col2)) {
                    if (col !in setOf("id", "null", "true", "false") && isEntityColumn(alias)) {
                        add(QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = col,
                            source = "native @Query JOIN: $funName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.NATIVE_SQL
                        ))
                    }
                }
            }

            // Extract ORDER BY columns
            val orderByRegex = Regex("""ORDER\s+BY\s+([\w.,\s]+?)(?:\s+LIMIT|\s*$)""", RegexOption.IGNORE_CASE)
            orderByRegex.find(normalized)?.let { orderByMatch ->
                val colNameRegex = Regex("""(?:(\w+)\.)?(\w+)\s*(?:ASC|DESC|,)?""", RegexOption.IGNORE_CASE)
                colNameRegex.findAll(orderByMatch.groupValues[1]).forEach { match ->
                    val alias = match.groupValues[1].ifEmpty { null }
                    val col = match.groupValues[2].lowercase()
                    if (col !in ORDER_BY_KEYWORDS && isEntityColumn(alias)) {
                        add(QueryColumn(
                            tableName = tableMapping.tableName,
                            columnName = col,
                            source = "native @Query ORDER BY: $funName",
                            filePath = filePath,
                            lineNumber = lineNumber,
                            queryType = QueryType.NATIVE_SQL
                        ))
                    }
                }
            }
        }.distinctBy { it.columnName }
    }

    // ========== Utility ==========

    private fun isPrecededByQueryAnnotation(lines: List<String>, funLineIndex: Int): Boolean =
        (1..30).any { offset ->
            val idx = funLineIndex - offset
            if (idx < 0) return false
            val line = lines[idx].trim()
            when {
                QUERY_ANNOTATION_START.containsMatchIn(line) -> true
                FUN_DECLARATION_REGEX.containsMatchIn(line) -> return false
                line.startsWith("interface ") -> return false
                else -> false
            }
        }

    private fun findFunAfterLine(lines: List<String>, startLine: Int): Int? =
        ((startLine + 1) until minOf(lines.size, startLine + 10))
            .firstOrNull { FUN_DECLARATION_REGEX.containsMatchIn(lines[it]) }

}
