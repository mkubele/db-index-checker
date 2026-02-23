package cz.kubele.gradle.dbindexchecker.report

import cz.kubele.gradle.dbindexchecker.model.BaselineComparison
import cz.kubele.gradle.dbindexchecker.model.BaselineIssue
import cz.kubele.gradle.dbindexchecker.model.MissingIndex
import java.io.File
import java.time.Instant

object BaselineManager {

    private val ISSUE_COMPARATOR = compareBy<BaselineIssue>({ it.serviceName.lowercase() }, { it.tableName.lowercase() }, { it.columnName.lowercase() })
    private val MISSING_COMPARATOR = compareBy<MissingIndex>({ it.serviceName.lowercase() }, { it.tableName.lowercase() }, { it.columnName.lowercase() })

    fun compare(currentMissing: List<MissingIndex>, baselineIssues: List<BaselineIssue>): BaselineComparison {
        val currentByKey = currentMissing.associateBy { issueKey(it.serviceName, it.tableName, it.columnName) }
        val baselineByKey = baselineIssues.associateBy { issueKey(it.serviceName, it.tableName, it.columnName) }

        return BaselineComparison(
            current = currentMissing.sortedWith(MISSING_COMPARATOR),
            newIssues = currentByKey.filterKeys { it !in baselineByKey }.values.sortedWith(MISSING_COMPARATOR),
            existingIssues = currentByKey.filterKeys { it in baselineByKey }.values.sortedWith(MISSING_COMPARATOR),
            resolvedIssues = baselineByKey.filterKeys { it !in currentByKey }.values.sortedWith(ISSUE_COMPARATOR)
        )
    }

    fun writeBaseline(file: File, missingIndexes: List<MissingIndex>) {
        file.parentFile?.mkdirs()
        val unique = missingIndexes
            .map { BaselineIssue(it.serviceName, it.tableName, it.columnName) }
            .distinctBy { issueKey(it.serviceName, it.tableName, it.columnName) }
            .sortedWith(ISSUE_COMPARATOR)

        file.writeText(buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"generatedAt\": ${jsonStr(Instant.now().toString())},")
            appendLine("  \"issues\": [")
            unique.forEachIndexed { i, issue ->
                val comma = if (i < unique.size - 1) "," else ""
                appendLine("    {\"service\": ${jsonStr(issue.serviceName)}, \"table\": ${jsonStr(issue.tableName)}, \"column\": ${jsonStr(issue.columnName)}}$comma")
            }
            appendLine("  ]")
            appendLine("}")
        })
    }

    fun readBaseline(file: File): List<BaselineIssue> {
        if (!file.exists()) return emptyList()
        val content = file.readText()
        val objectPattern = Regex("\\{\\s*\"service\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"table\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"column\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*}")

        return objectPattern.findAll(content)
            .map { match ->
                BaselineIssue(
                    serviceName = unescapeJson(match.groupValues[1]),
                    tableName = unescapeJson(match.groupValues[2]),
                    columnName = unescapeJson(match.groupValues[3])
                )
            }
            .distinctBy { issueKey(it.serviceName, it.tableName, it.columnName) }
            .toList()
    }

    private fun issueKey(service: String, table: String, column: String): String =
        "${service.lowercase()}|${table.lowercase()}|${column.lowercase()}"

    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun unescapeJson(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(s[i])
                i++
            }
        }
    }
}
