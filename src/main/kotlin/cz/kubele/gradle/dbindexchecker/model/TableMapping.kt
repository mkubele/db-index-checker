package cz.kubele.gradle.dbindexchecker.model

/**
 * Maps an entity class to its database table and field-to-column mappings.
 */
data class TableMapping(
    val entityName: String,
    val tableName: String,
    val fieldToColumn: Map<String, String>  // entityField -> db_column_name
)
