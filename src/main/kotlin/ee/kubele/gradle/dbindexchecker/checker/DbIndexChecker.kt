package ee.kubele.gradle.dbindexchecker.checker

import ee.kubele.gradle.dbindexchecker.model.IndexedColumn
import ee.kubele.gradle.dbindexchecker.model.MissingIndex
import ee.kubele.gradle.dbindexchecker.model.QueryColumn

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

		// Build composite index structures: table -> list of index column lists (sorted by position)
		val compositeIndexes = indexedColumns
			.groupBy { it.tableName.lowercase() to it.indexName }
			.mapValues { (_, cols) -> cols.sortedBy { it.compositePosition }.map { it.columnName.lowercase() } }
			.entries.groupBy({ it.key.first }, { it.value })

		// Group query columns by source query to detect composite index coverage
		val queryColumnsBySource = queryColumns
			.groupBy { Triple(it.tableName.lowercase(), it.source, it.filePath) }
			.mapValues { (_, cols) -> cols.map { it.columnName.lowercase() }.toSet() }

		// Track seen table+column pairs for deduplication
		val seen = mutableSetOf<String>()

		return queryColumns.mapNotNull { qc ->
			val tableLower = qc.tableName.lowercase()
			val columnLower = qc.columnName.lowercase()
			val queryCols = queryColumnsBySource[Triple(tableLower, qc.source, qc.filePath)] ?: emptySet()

			when {
				tableLower in excludeTablesLower -> null
				columnLower in excludeColumnsLower -> null
				"$tableLower.$columnLower" in excludeFindingsLower -> null
				columnLower == "id" -> null
				indexedLookup[tableLower]?.contains(columnLower) == true -> null
				isCoveredByCompositeIndex(columnLower, queryCols, compositeIndexes[tableLower] ?: emptyList()) -> null
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

	/**
	 * Checks if a column is covered by a composite index when the query uses
	 * all preceding columns in that index (left prefix rule).
	 */
	private fun isCoveredByCompositeIndex(
		column: String,
		queryColumns: Set<String>,
		compositeIndexes: List<List<String>>
	): Boolean = compositeIndexes.any { indexColumns ->
		val colPos = indexColumns.indexOf(column)
		colPos > 0 && (0 until colPos).all { indexColumns[it] in queryColumns }
	}
}
