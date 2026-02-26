package ee.kubele.gradle.dbindexchecker.model

/**
 * Represents a database column that is used in a repository query (WHERE, JOIN, ORDER BY).
 */
data class QueryColumn(
    val tableName: String,
    val columnName: String,
    val source: String,       // e.g., method name or query snippet
    val filePath: String,
    val lineNumber: Int,
    val queryType: QueryType
)

enum class QueryType {
    DERIVED_QUERY,
    JPQL,
    NATIVE_SQL
}
