package ee.kubele.gradle.dbindexchecker.parser

import ee.kubele.gradle.dbindexchecker.model.TableMapping
import java.io.File

/**
 * Parses Kotlin entity files to extract table name and field-to-column mappings.
 * Handles @Table, @Column, @JoinColumn annotations and default Hibernate naming.
 */
object EntityParser {

    private val TABLE_ANNOTATION = Regex("""@Table\s*\(\s*name\s*=\s*"([^"]+)"""")
    private val COLUMN_ANNOTATION = Regex("""@Column\s*\([^)]*name\s*=\s*"([^"]+)"[^)]*\)""")
    private val JOIN_COLUMN_ANNOTATION = Regex("""@JoinColumn\s*\([^)]*name\s*=\s*"([^"]+)"[^)]*\)""")
    private val ENTITY_ANNOTATION = Regex("""@Entity""")
    private val CLASS_DECLARATION = Regex("""class\s+(\w+)""")
    private val FIELD_DECLARATION = Regex("""^\s*(?:var|val)\s+(\w+)\s*:\s*(\S+)""")
    private val ONE_TO_MANY = Regex("""@OneToMany""")
    private val MANY_TO_MANY = Regex("""@ManyToMany""")
    private val JOIN_TABLE = Regex("""@JoinTable""")

    fun parseEntities(entityDir: File): Map<String, TableMapping> {
        if (!entityDir.exists()) return emptyMap()

        return entityDir.walkTopDown()
            .filter { it.extension == "kt" }
            .mapNotNull { parseEntityFile(it) }
            .associateBy { it.entityName }
    }

    fun parseEntityFile(file: File): TableMapping? {
        val content = file.readText()
        val lines = content.lines()

        if (!ENTITY_ANNOTATION.containsMatchIn(content)) return null

        val tableName = TABLE_ANNOTATION.find(content)?.groupValues?.get(1) ?: return null
        val className = CLASS_DECLARATION.find(content)?.groupValues?.get(1) ?: return null

        val fieldToColumn = buildMap {
            put("id", "id")

            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                // Skip @OneToMany and @ManyToMany - these are collection mappings, not columns
                if (ONE_TO_MANY.containsMatchIn(line) || MANY_TO_MANY.containsMatchIn(line) || JOIN_TABLE.containsMatchIn(line)) {
                    i++
                    continue
                }

                // Check for @Column annotation
                val columnMatch = COLUMN_ANNOTATION.find(line)
                if (columnMatch != null) {
                    findFieldName(lines, i)?.let { put(it, columnMatch.groupValues[1]) }
                    i++
                    continue
                }

                // Check for @JoinColumn annotation (for @ManyToOne and @OneToOne)
                val joinColumnMatch = JOIN_COLUMN_ANNOTATION.find(line)
                if (joinColumnMatch != null) {
                    findFieldName(lines, i)?.let { put(it, joinColumnMatch.groupValues[1]) }
                    i++
                    continue
                }

                // Check for plain field declarations (no explicit @Column)
                FIELD_DECLARATION.find(line)?.let { match ->
                    val fieldName = match.groupValues[1]
                    if (!isRelationshipField(lines, i) && fieldName !in this) {
                        put(fieldName, camelToSnake(fieldName))
                    }
                }

                i++
            }

            // Add base GeneralEntity fields
            putIfAbsent("createdAt", "created_at")
            putIfAbsent("updatedAt", "updated_at")
        }

        return TableMapping(
            entityName = className,
            tableName = tableName,
            fieldToColumn = fieldToColumn
        )
    }

    private fun findFieldName(lines: List<String>, startIndex: Int): String? =
        (0..5).firstNotNullOfOrNull { offset ->
            lines.getOrNull(startIndex + offset)?.let { FIELD_DECLARATION.find(it)?.groupValues?.get(1) }
        }

    private fun isRelationshipField(lines: List<String>, fieldLineIndex: Int): Boolean =
        (1..5).any { offset ->
            val idx = fieldLineIndex - offset
            if (idx < 0) return false
            val line = lines[idx]
            when {
                ONE_TO_MANY.containsMatchIn(line) || MANY_TO_MANY.containsMatchIn(line) -> true
                FIELD_DECLARATION.containsMatchIn(line) || line.isBlank() -> return false
                else -> false
            }
        }

    fun camelToSnake(name: String): String =
        name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .lowercase()
}
