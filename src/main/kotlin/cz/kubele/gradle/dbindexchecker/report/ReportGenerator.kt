package cz.kubele.gradle.dbindexchecker.report

import cz.kubele.gradle.dbindexchecker.model.MissingIndex
import cz.kubele.gradle.dbindexchecker.model.QueryType
import org.gradle.api.logging.Logger
import java.io.File

object ReportGenerator {

    fun generate(
        missingIndexes: List<MissingIndex>,
        logger: Logger,
        htmlFile: File,
        jsonFile: File
    ) {
        printConsole(missingIndexes, logger)
        writeHtml(missingIndexes, htmlFile)
        writeJson(missingIndexes, jsonFile)

        logger.lifecycle("Reports written to:")
        logger.lifecycle("  HTML: ${htmlFile.absolutePath}")
        logger.lifecycle("  JSON: ${jsonFile.absolutePath}")
    }

    private fun printConsole(missingIndexes: List<MissingIndex>, logger: Logger) {
        if (missingIndexes.isEmpty()) {
            logger.lifecycle("Index Checker: All queried columns have indexes!")
            return
        }

        logger.lifecycle("")
        logger.warn("Index Checker: Found ${missingIndexes.size} potentially missing indexes")
        logger.lifecycle("")

        val byService = missingIndexes.groupBy { it.serviceName }
        for ((serviceName, serviceIndexes) in byService.entries.sortedBy { it.key }) {
            logger.lifecycle("  $serviceName:")
            val byTable = serviceIndexes.groupBy { it.tableName }
            for ((tableName, tableIndexes) in byTable.entries.sortedBy { it.key.lowercase() }) {
                logger.lifecycle("    Table '$tableName':")
                for (mi in tableIndexes.sortedBy { it.columnName.lowercase() }) {
                    logger.warn("      - Column '${mi.columnName}' used in ${formatQueryDescription(mi)} (${mi.repositoryFile}:${mi.lineNumber})")
                }
                logger.lifecycle("")
            }
        }

        logger.lifecycle("Total: ${missingIndexes.size} potentially missing indexes across ${byService.size} services")
        logger.lifecycle("")
    }

    private fun writeHtml(missingIndexes: List<MissingIndex>, file: File) {
        file.parentFile.mkdirs()
        val total = missingIndexes.size
        val byService = missingIndexes.groupBy { it.serviceName }

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

            if (total == 0) {
                appendLine("<h1>Index Check Report</h1>")
                appendLine("<p class=\"success\">All queried columns have indexes!</p>")
            } else {
                appendLine("<h1>Index Check Report</h1>")
                appendLine("<p class=\"summary\">Found <strong>$total</strong> potentially missing indexes across <strong>${byService.size}</strong> services</p>")
                appendLine("<table>")
                appendLine("<thead><tr><th>Service</th><th>Table</th><th>Column</th><th>Query</th><th>Source</th></tr></thead>")
                appendLine("<tbody>")

                for ((serviceName, serviceIndexes) in byService.entries.sortedBy { it.key }) {
                    val byTable = serviceIndexes.groupBy { it.tableName }
                    for ((tableName, tableIndexes) in byTable.entries.sortedBy { it.key.lowercase() }) {
                        for (mi in tableIndexes.sortedBy { it.columnName.lowercase() }) {
                            val queryDesc = escapeHtml(formatQueryDescription(mi))
                            val filePath = escapeHtml(mi.repositoryFile)
                            appendLine("<tr>")
                            appendLine("  <td>${escapeHtml(serviceName)}</td>")
                            appendLine("  <td><code>${escapeHtml(tableName)}</code></td>")
                            appendLine("  <td><code>${escapeHtml(mi.columnName)}</code></td>")
                            appendLine("  <td>$queryDesc</td>")
                            appendLine("  <td><a href=\"#\" onclick=\"openInIde('${escapeJs(mi.repositoryFile)}', ${mi.lineNumber}); return false;\">${escapeHtml(shortPath(mi.repositoryFile))}:${mi.lineNumber}</a></td>")
                            appendLine("</tr>")
                        }
                    }
                }

                appendLine("</tbody>")
                appendLine("</table>")
            }

            appendLine("<script>")
            appendLine(JS)
            appendLine("</script>")
            appendLine("</body>")
            appendLine("</html>")
        })
    }

    private fun writeJson(missingIndexes: List<MissingIndex>, file: File) {
        file.parentFile.mkdirs()

        file.writeText(buildString {
            appendLine("{")
            appendLine("  \"total\": ${missingIndexes.size},")
            appendLine("  \"services\": ${missingIndexes.map { it.serviceName }.distinct().size},")
            appendLine("  \"issues\": [")

            missingIndexes.forEachIndexed { i, mi ->
                val comma = if (i < missingIndexes.size - 1) "," else ""
                appendLine("    {")
                appendLine("      \"service\": ${jsonStr(mi.serviceName)},")
                appendLine("      \"table\": ${jsonStr(mi.tableName)},")
                appendLine("      \"column\": ${jsonStr(mi.columnName)},")
                appendLine("      \"queryType\": ${jsonStr(mi.queryType.name)},")
                appendLine("      \"querySource\": ${jsonStr(mi.querySource)},")
                appendLine("      \"file\": ${jsonStr(mi.repositoryFile)},")
                appendLine("      \"line\": ${mi.lineNumber}")
                appendLine("    }$comma")
            }

            appendLine("  ]")
            appendLine("}")
        })
    }

    private fun formatQueryDescription(mi: MissingIndex): String {
        return when (mi.queryType) {
            QueryType.DERIVED_QUERY -> "query '${mi.querySource}'"
            QueryType.JPQL -> "JPQL query"
            QueryType.NATIVE_SQL -> "native SQL query"
        }
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
                    // Fallback: try idea:// protocol
                    window.location = 'idea://open?file=' + encodeURIComponent(file) + '&line=' + line;
                });
        }
    """.trimIndent()

    private val CSS = """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 2rem; color: #333; }
        h1 { color: #1a1a1a; border-bottom: 2px solid #e0e0e0; padding-bottom: 0.5rem; }
        .summary { font-size: 1.1rem; color: #b45309; background: #fef3c7; padding: 0.75rem 1rem; border-radius: 6px; }
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
