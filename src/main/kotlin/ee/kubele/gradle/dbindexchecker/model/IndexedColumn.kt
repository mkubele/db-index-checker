package ee.kubele.gradle.dbindexchecker.model

/**
 * Represents a database column that has an index defined in Liquibase.
 */
data class IndexedColumn(
    val tableName: String,
    val columnName: String,
    val indexName: String,
    val filePath: String,
    val isUnique: Boolean = false,
    val isPartial: Boolean = false,
    val compositePosition: Int = 0  // 0-based position in composite index
)
