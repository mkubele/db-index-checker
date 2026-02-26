package ee.kubele.gradle.dbindexchecker.report

import ee.kubele.gradle.dbindexchecker.model.BaselineComparison
import ee.kubele.gradle.dbindexchecker.model.BaselineIssue
import ee.kubele.gradle.dbindexchecker.model.MissingIndex
import ee.kubele.gradle.dbindexchecker.model.QueryType
import org.gradle.api.logging.Logger
import java.io.File

object ReportGenerator {

    private val MISSING_COMPARATOR = compareBy<MissingIndex>({ it.serviceName.lowercase() }, { it.tableName.lowercase() }, { it.columnName.lowercase() })
    private val ISSUE_COMPARATOR = compareBy<BaselineIssue>({ it.serviceName.lowercase() }, { it.tableName.lowercase() }, { it.columnName.lowercase() })

    fun generate(
        comparison: BaselineComparison,
        logger: Logger,
        htmlFile: File,
        jsonFile: File,
        warnOnExistingMissing: Boolean
    ) {
        printConsole(comparison, logger, warnOnExistingMissing)
        writeHtml(comparison, htmlFile)
        writeJson(comparison, jsonFile)

        logger.lifecycle("Reports written to:")
        logger.lifecycle("  HTML: ${htmlFile.absolutePath}")
        logger.lifecycle("  JSON: ${jsonFile.absolutePath}")
    }

    private fun printConsole(comparison: BaselineComparison, logger: Logger, warnOnExistingMissing: Boolean) {
        if (comparison.current.isEmpty() && comparison.resolvedIssues.isEmpty()) {
            logger.lifecycle("Index Checker: All queried columns have indexes!")
            return
        }

        logger.lifecycle("")
        logger.warn("Index Checker: Found ${comparison.current.size} potentially missing indexes")
        logger.lifecycle("  New: ${comparison.newIssues.size}, Existing: ${comparison.existingIssues.size}, Resolved: ${comparison.resolvedIssues.size}")
        logger.lifecycle("")

        printIssueSection("New missing indexes", comparison.newIssues, logger, asWarning = true)

        if (warnOnExistingMissing) {
            printIssueSection("Existing baseline issues", comparison.existingIssues, logger, asWarning = true)
        } else if (comparison.existingIssues.isNotEmpty()) {
            logger.lifecycle("  Existing baseline issues: ${comparison.existingIssues.size} (suppressed)")
            logger.lifecycle("")
        }

        if (comparison.resolvedIssues.isNotEmpty()) {
            logger.lifecycle("  Resolved since baseline:")
            comparison.resolvedIssues
                .groupBy { it.serviceName }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (serviceName, serviceIssues) ->
                    logger.lifecycle("    $serviceName:")
                    serviceIssues
                        .groupBy { it.tableName }
                        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                        .forEach { (tableName, tableIssues) ->
                            val columns = tableIssues.map { it.columnName }.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(", ")
                            logger.lifecycle("      - $tableName: $columns")
                        }
                }
            logger.lifecycle("")
        }

        val serviceCount = comparison.current.map { it.serviceName }.distinctBy { it.lowercase() }.size
        logger.lifecycle("Total: ${comparison.current.size} potentially missing indexes across $serviceCount services")
        logger.lifecycle("")
    }

    private fun printIssueSection(title: String, issues: List<MissingIndex>, logger: Logger, asWarning: Boolean) {
        if (issues.isEmpty()) return

        logger.lifecycle("  $title:")
        issues
            .groupBy { it.serviceName }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .forEach { (serviceName, serviceIssues) ->
                logger.lifecycle("    $serviceName:")
                serviceIssues
                    .groupBy { it.tableName }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (tableName, tableIssues) ->
                        logger.lifecycle("      Table '$tableName':")
                        tableIssues
                            .sortedBy { it.columnName.lowercase() }
                            .forEach { issue ->
                                val message = "        - Column '${issue.columnName}' used in ${formatQueryDescription(issue)} (${issue.repositoryFile}:${issue.lineNumber})"
                                if (asWarning) logger.warn(message) else logger.lifecycle(message)
                            }
                    }
            }
        logger.lifecycle("")
    }

    private fun writeHtml(comparison: BaselineComparison, file: File) {
        file.parentFile.mkdirs()

        file.writeText(buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>Index Check Report</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<h1>Index Check Report</h1>")

            if (comparison.current.isEmpty() && comparison.resolvedIssues.isEmpty()) {
                appendLine("<p class=\"success\">All queried columns have indexes!</p>")
            } else {
                appendLine("<p class=\"summary\">Total <strong>${comparison.current.size}</strong> missing indexes | New: <strong>${comparison.newIssues.size}</strong> | Existing: <strong>${comparison.existingIssues.size}</strong> | Resolved: <strong>${comparison.resolvedIssues.size}</strong></p>")
                appendIssueTable("New missing indexes", comparison.newIssues)
                appendIssueTable("Existing baseline issues", comparison.existingIssues)
                appendResolvedTable("Resolved since baseline", comparison.resolvedIssues)
            }

            appendLine("<script>")
            appendLine(JS)
            appendLine("</script>")
            appendLine("</body>")
            appendLine("</html>")
        })
    }

    private fun StringBuilder.appendIssueTable(title: String, issues: List<MissingIndex>) {
        if (issues.isEmpty()) return

        appendLine("<h2>$title</h2>")
        appendLine("<table>")
        appendLine("<thead><tr><th>Service</th><th>Table</th><th>Column</th><th>Query</th><th>Source</th></tr></thead>")
        appendLine("<tbody>")

        for (issue in issues.sortedWith(MISSING_COMPARATOR)) {
            appendLine("<tr>")
            appendLine("  <td>${escapeHtml(issue.serviceName)}</td>")
            appendLine("  <td><code>${escapeHtml(issue.tableName)}</code></td>")
            appendLine("  <td><code>${escapeHtml(issue.columnName)}</code></td>")
            appendLine("  <td>${escapeHtml(formatQueryDescription(issue))}</td>")
            appendLine("  <td><a href=\"#\" onclick=\"openInIde('${escapeJs(issue.repositoryFile)}', ${issue.lineNumber}); return false;\">${escapeHtml(shortPath(issue.repositoryFile))}:${issue.lineNumber}</a></td>")
            appendLine("</tr>")
        }

        appendLine("</tbody>")
        appendLine("</table>")
    }

    private fun StringBuilder.appendResolvedTable(title: String, issues: List<BaselineIssue>) {
        if (issues.isEmpty()) return

        appendLine("<h2>$title</h2>")
        appendLine("<table>")
        appendLine("<thead><tr><th>Service</th><th>Table</th><th>Column</th></tr></thead>")
        appendLine("<tbody>")

        for (issue in issues.sortedWith(ISSUE_COMPARATOR)) {
            appendLine("<tr>")
            appendLine("  <td>${escapeHtml(issue.serviceName)}</td>")
            appendLine("  <td><code>${escapeHtml(issue.tableName)}</code></td>")
            appendLine("  <td><code>${escapeHtml(issue.columnName)}</code></td>")
            appendLine("</tr>")
        }

        appendLine("</tbody>")
        appendLine("</table>")
    }

    private fun writeJson(comparison: BaselineComparison, file: File) {
        file.parentFile.mkdirs()

        file.writeText(buildString {
            appendLine("{")
            appendLine("  \"total\": ${comparison.current.size},")
            appendLine("  \"new\": ${comparison.newIssues.size},")
            appendLine("  \"existing\": ${comparison.existingIssues.size},")
            appendLine("  \"resolved\": ${comparison.resolvedIssues.size},")
            appendLine("  \"issues\": {")
            appendLine("    \"new\": [")
            comparison.newIssues.forEachIndexed { i, issue ->
                appendMissingIssue(issue, i < comparison.newIssues.size - 1)
            }
            appendLine("    ],")
            appendLine("    \"existing\": [")
            comparison.existingIssues.forEachIndexed { i, issue ->
                appendMissingIssue(issue, i < comparison.existingIssues.size - 1)
            }
            appendLine("    ],")
            appendLine("    \"resolved\": [")
            comparison.resolvedIssues.forEachIndexed { i, issue ->
                val comma = if (i < comparison.resolvedIssues.size - 1) "," else ""
                appendLine("      {\"service\": ${jsonStr(issue.serviceName)}, \"table\": ${jsonStr(issue.tableName)}, \"column\": ${jsonStr(issue.columnName)}}$comma")
            }
            appendLine("    ]")
            appendLine("  },")
            appendLine("  \"allCurrent\": [")
            comparison.current.forEachIndexed { i, issue ->
                appendMissingIssue(issue, i < comparison.current.size - 1)
            }
            appendLine("  ]")
            appendLine("}")
        })
    }

    private fun StringBuilder.appendMissingIssue(issue: MissingIndex, withComma: Boolean) {
        val comma = if (withComma) "," else ""
        appendLine("      {")
        appendLine("        \"service\": ${jsonStr(issue.serviceName)},")
        appendLine("        \"table\": ${jsonStr(issue.tableName)},")
        appendLine("        \"column\": ${jsonStr(issue.columnName)},")
        appendLine("        \"queryType\": ${jsonStr(issue.queryType.name)},")
        appendLine("        \"querySource\": ${jsonStr(issue.querySource)},")
        appendLine("        \"file\": ${jsonStr(issue.repositoryFile)},")
        appendLine("        \"line\": ${issue.lineNumber}")
        appendLine("      }$comma")
    }

    private fun formatQueryDescription(mi: MissingIndex): String = when (mi.queryType) {
        QueryType.DERIVED_QUERY -> "query '${mi.querySource}'"
        QueryType.JPQL -> "JPQL query"
        QueryType.NATIVE_SQL -> "native SQL query"
    }

    private fun shortPath(path: String): String {
        val idx = path.indexOf("-service/")
        if (idx != -1) return path.substring(path.lastIndexOf('/', idx) + 1)
        val idx2 = path.indexOf("-app/")
        if (idx2 != -1) return path.substring(path.lastIndexOf('/', idx2) + 1)
        return path.substringAfterLast('/')
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")

    private fun jsonStr(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private val JS = """
        function openInIde(file, line) {
            fetch('http://localhost:63342/api/file/' + encodeURIComponent(file) + ':' + line)
                .catch(function() {
                    window.location = 'idea://open?file=' + encodeURIComponent(file) + '&line=' + line;
                });
        }
    """.trimIndent()

    private val CSS = """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 2rem; color: #333; }
        h1 { color: #1a1a1a; border-bottom: 2px solid #e0e0e0; padding-bottom: 0.5rem; }
        h2 { margin-top: 1.6rem; color: #1f2937; }
        .summary { font-size: 1.1rem; color: #1f2937; background: #e5e7eb; padding: 0.75rem 1rem; border-radius: 6px; }
        .success { font-size: 1.1rem; color: #065f46; background: #d1fae5; padding: 0.75rem 1rem; border-radius: 6px; }
        table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
        th { background: #f3f4f6; text-align: left; padding: 0.6rem 0.8rem; border-bottom: 2px solid #d1d5db; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.03em; }
        td { padding: 0.5rem 0.8rem; border-bottom: 1px solid #e5e7eb; font-size: 0.9rem; }
        tr:hover { background: #f9fafb; }
        code { background: #f3f4f6; padding: 0.15rem 0.4rem; border-radius: 3px; font-size: 0.85rem; }
        a { color: #2563eb; text-decoration: none; }
        a:hover { text-decoration: underline; }
    """.trimIndent()
}
