package cz.kubele.gradle.dbindexchecker.parser

import cz.kubele.gradle.dbindexchecker.model.TableMapping
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
    private val FIELD_DECLARATION = Regex("""^\s*(?:(?:var|val)\s+(\w+)\s*:\s*(\S+))""")
    private val MANY_TO_ONE = Regex("""@ManyToOne""")
    private val ONE_TO_ONE = Regex("""@OneToOne""")
    private val ONE_TO_MANY = Regex("""@OneToMany""")
    private val MANY_TO_MANY = Regex("""@ManyToMany""")
    private val MAPPED_SUPERCLASS = Regex("""@MappedSuperclass""")
    private val ID_ANNOTATION = Regex("""@Id""")
    private val JOIN_TABLE = Regex("""@JoinTable""")

    fun parseEntities(entityDir: File): Map<String, TableMapping> {
        if (!entityDir.exists()) return emptyMap()

        val results = mutableMapOf<String, TableMapping>()

        entityDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val mapping = parseEntityFile(file)
                if (mapping != null) {
                    results[mapping.entityName] = mapping
                }
            }

        return results
    }

    fun parseEntityFile(file: File): TableMapping? {
        val content = file.readText()
        val lines = content.lines()

        // Must have @Entity annotation
        if (!ENTITY_ANNOTATION.containsMatchIn(content)) return null

        // Extract table name from @Table annotation
        val tableName = TABLE_ANNOTATION.find(content)?.groupValues?.get(1) ?: return null

        // Extract class name
        val className = CLASS_DECLARATION.find(content)?.groupValues?.get(1) ?: return null

        val fieldToColumn = mutableMapOf<String, String>()

        // Always add 'id' as it's the primary key
        fieldToColumn["id"] = "id"

        // Parse fields with annotations
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
                val explicitColumnName = columnMatch.groupValues[1]
                // Find the field declaration on this line or subsequent lines
                val fieldName = findFieldName(lines, i)
                if (fieldName != null) {
                    fieldToColumn[fieldName] = explicitColumnName
                }
                i++
                continue
            }

            // Check for @JoinColumn annotation (for @ManyToOne and @OneToOne)
            val joinColumnMatch = JOIN_COLUMN_ANNOTATION.find(line)
            if (joinColumnMatch != null) {
                val explicitColumnName = joinColumnMatch.groupValues[1]
                val fieldName = findFieldName(lines, i)
                if (fieldName != null) {
                    fieldToColumn[fieldName] = explicitColumnName
                }
                i++
                continue
            }

            // Check for plain field declarations (no explicit @Column)
            val fieldMatch = FIELD_DECLARATION.find(line)
            if (fieldMatch != null) {
                val fieldName = fieldMatch.groupValues[1]
                // Skip fields that are annotated with relationship annotations on previous lines
                if (!isRelationshipField(lines, i) && !fieldToColumn.containsKey(fieldName)) {
                    // Default Hibernate naming: camelCase → snake_case
                    fieldToColumn[fieldName] = camelToSnake(fieldName)
                }
            }

            i++
        }

        // Add base GeneralEntity fields
        fieldToColumn.putIfAbsent("createdAt", "created_at")
        fieldToColumn.putIfAbsent("updatedAt", "updated_at")

        return TableMapping(
            entityName = className,
            tableName = tableName,
            fieldToColumn = fieldToColumn
        )
    }

    private fun findFieldName(lines: List<String>, startIndex: Int): String? {
        // Look at current line and up to 5 lines ahead for the field declaration
        for (offset in 0..5) {
            val idx = startIndex + offset
            if (idx >= lines.size) break
            val match = FIELD_DECLARATION.find(lines[idx])
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun isRelationshipField(lines: List<String>, fieldLineIndex: Int): Boolean {
        // Look at up to 5 preceding lines for relationship annotations
        for (offset in 1..5) {
            val idx = fieldLineIndex - offset
            if (idx < 0) break
            val line = lines[idx]
            if (ONE_TO_MANY.containsMatchIn(line) || MANY_TO_MANY.containsMatchIn(line)) return true
            // If we hit a var/val declaration or a blank line, stop looking
            if (FIELD_DECLARATION.containsMatchIn(line) || line.isBlank()) break
        }
        return false
    }

    fun camelToSnake(name: String): String {
        return name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .lowercase()
    }
}
