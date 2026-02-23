package cz.kubele.gradle.dbindexchecker.model

data class BaselineComparison(
    val current: List<MissingIndex>,
    val newIssues: List<MissingIndex>,
    val existingIssues: List<MissingIndex>,
    val resolvedIssues: List<BaselineIssue>
)

data class BaselineIssue(
    val serviceName: String,
    val tableName: String,
    val columnName: String
)
