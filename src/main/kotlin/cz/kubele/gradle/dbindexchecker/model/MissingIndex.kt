package cz.kubele.gradle.dbindexchecker.model

/**
 * Represents a column used in a query that lacks an index.
 */
data class MissingIndex(
    val serviceName: String,
    val tableName: String,
    val columnName: String,
    val querySource: String,
    val repositoryFile: String,
    val lineNumber: Int,
    val queryType: QueryType
)
