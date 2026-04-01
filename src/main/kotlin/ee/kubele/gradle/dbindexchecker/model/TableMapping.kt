package ee.kubele.gradle.dbindexchecker.model

/**
 * Maps an entity class to its database table and field-to-column mappings.
 */
data class TableMapping(
	val entityName: String,
	val tableName: String,
	val fieldToColumn: Map<String, String>,  // entityField -> db_column_name
	val excludedRelationshipFields: Set<String> = emptySet()  // fields with no DB column (e.g. @OneToMany)
)
