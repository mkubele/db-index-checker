package cz.kubele.gradle.dbindexchecker.checker

import cz.kubele.gradle.dbindexchecker.model.IndexedColumn
import cz.kubele.gradle.dbindexchecker.model.MissingIndex
import cz.kubele.gradle.dbindexchecker.model.QueryColumn

/**
 * Compares query columns against indexed columns and reports missing indexes.
 */
object DbIndexChecker {

    fun findMissingIndexes(
        serviceName: String,
        queryColumns: List<QueryColumn>,
        indexedColumns: List<IndexedColumn>,
        excludeTables: Set<String>,
        excludeColumns: Set<String>,
        excludeFindings: Set<String> = emptySet()
    ): List<MissingIndex> {
        val excludeTablesLower = excludeTables.mapTo(mutableSetOf()) { it.lowercase() }
        val excludeColumnsLower = excludeColumns.mapTo(mutableSetOf()) { it.lowercase() }
        val excludeFindingsLower = excludeFindings.mapTo(mutableSetOf()) { it.lowercase().trim() }

        // Build lookup: table -> set of indexed column names
        // A column is considered indexed if it appears as a standalone index
        // or at position 0 of a composite index (leftmost prefix is usable)
        val indexedLookup = indexedColumns
            .filter { it.compositePosition == 0 }
            .groupBy({ it.tableName.lowercase() }, { it.columnName.lowercase() })
            .mapValues { (_, cols) -> cols.toSet() }

        // Track seen table+column pairs for deduplication
        val seen = mutableSetOf<String>()

        return queryColumns.mapNotNull { qc ->
            val tableLower = qc.tableName.lowercase()
            val columnLower = qc.columnName.lowercase()

            when {
                tableLower in excludeTablesLower -> null
                columnLower in excludeColumnsLower -> null
                "$tableLower.$columnLower" in excludeFindingsLower -> null
                columnLower == "id" -> null
                indexedLookup[tableLower]?.contains(columnLower) == true -> null
                !seen.add("$tableLower:$columnLower") -> null
                else -> MissingIndex(
                    serviceName = serviceName,
                    tableName = qc.tableName,
                    columnName = qc.columnName,
                    querySource = qc.source,
                    repositoryFile = qc.filePath,
                    lineNumber = qc.lineNumber,
                    queryType = qc.queryType
                )
            }
        }.sortedWith(compareBy({ it.tableName.lowercase() }, { it.columnName.lowercase() }))
    }
}
