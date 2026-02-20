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
        excludeColumns: Set<String>
    ): List<MissingIndex> {
        val excludeTablesLower = excludeTables.map { it.lowercase() }.toSet()
        val excludeColumnsLower = excludeColumns.map { it.lowercase() }.toSet()

        // Build lookup: table -> set of indexed column names
        // A column is considered indexed if it appears as a standalone index
        // or at position 0 of a composite index (leftmost prefix is usable)
        val indexedLookup = buildIndexedLookup(indexedColumns)

        // Track seen table+column pairs for deduplication
        val seen = mutableSetOf<String>()
        val missing = mutableListOf<MissingIndex>()

        for (qc in queryColumns) {
            val tableLower = qc.tableName.lowercase()
            val columnLower = qc.columnName.lowercase()

            // Skip excluded tables and columns
            if (tableLower in excludeTablesLower) continue
            if (columnLower in excludeColumnsLower) continue

            // Skip id columns - always primary key indexed
            if (columnLower == "id") continue

            // Check if the column has an index
            val indexedColumnsForTable = indexedLookup[tableLower]
            if (indexedColumnsForTable != null && columnLower in indexedColumnsForTable) {
                continue
            }

            // Deduplicate by table+column
            val dedupeKey = "$tableLower:$columnLower"
            if (dedupeKey in seen) continue
            seen.add(dedupeKey)

            missing.add(
                MissingIndex(
                    serviceName = serviceName,
                    tableName = qc.tableName,
                    columnName = qc.columnName,
                    querySource = qc.source,
                    repositoryFile = qc.filePath,
                    lineNumber = qc.lineNumber,
                    queryType = qc.queryType
                )
            )
        }

        return missing.sortedWith(compareBy({ it.tableName.lowercase() }, { it.columnName.lowercase() }))
    }

    private fun buildIndexedLookup(indexedColumns: List<IndexedColumn>): Map<String, Set<String>> {
        val lookup = mutableMapOf<String, MutableSet<String>>()
        for (ic in indexedColumns) {
            val tableLower = ic.tableName.lowercase()
            val columnLower = ic.columnName.lowercase()

            // Include standalone indexes and columns at position 0 of composite indexes
            if (ic.compositePosition == 0) {
                lookup.getOrPut(tableLower) { mutableSetOf() }.add(columnLower)
            }
        }
        return lookup
    }
}
