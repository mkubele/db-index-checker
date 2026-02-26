package ee.kubele.gradle.dbindexchecker

import ee.kubele.gradle.dbindexchecker.checker.DbIndexChecker
import ee.kubele.gradle.dbindexchecker.model.BaselineIssue
import ee.kubele.gradle.dbindexchecker.model.MissingIndex
import ee.kubele.gradle.dbindexchecker.model.TableMapping
import ee.kubele.gradle.dbindexchecker.report.BaselineManager
import ee.kubele.gradle.dbindexchecker.parser.EntityParser
import ee.kubele.gradle.dbindexchecker.parser.LiquibaseParser
import ee.kubele.gradle.dbindexchecker.parser.RepositoryParser
import ee.kubele.gradle.dbindexchecker.report.ReportGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

@CacheableTask
abstract class DbIndexCheckerTask : DefaultTask() {

    @get:Input
    abstract val failOnMissing: Property<Boolean>

    @get:Input
    abstract val failOnNewMissing: Property<Boolean>

    @get:Input
    abstract val warnOnExistingMissing: Property<Boolean>

    @get:Input
    abstract val baselineFilePath: Property<String>

    @get:Input
    abstract val excludeTables: ListProperty<String>

    @get:Input
    abstract val excludeColumns: ListProperty<String>

    @get:Input
    abstract val excludeFindings: ListProperty<String>

    @get:Input
    abstract val serviceNames: ListProperty<String>

    @get:Input
    abstract val entityDirNames: ListProperty<String>

    @get:Input
    abstract val repositoryDirNames: ListProperty<String>

    @get:Input
    abstract val liquibaseRelativePath: Property<String>

    @get:Internal
    abstract val rootProjectDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val kotlinSourceFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val liquibaseFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baselineInputFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val htmlReportFile: RegularFileProperty

    @get:OutputFile
    abstract val jsonReportFile: RegularFileProperty

    @get:Input
    abstract val writeBaseline: Property<Boolean>

    init {
        writeBaseline.convention(false)
        outputs.cacheIf { !writeBaseline.get() }
    }

	@Suppress("unused")
    @Option(option = "write-baseline", description = "Write current missing indexes into baseline JSON and exit without failing")
    fun configureWriteBaselineOption(value: Boolean) {
        writeBaseline.set(value)
    }

    @TaskAction
    fun check() {
        val rootDir = rootProjectDir.get().asFile
        val resolvedServiceNames = serviceNames.get().ifEmpty { discoverModules(rootDir) }

        if (resolvedServiceNames.isEmpty()) {
            // Single-module project: scan the root itself
            logger.lifecycle("Index Checker: Scanning single-module project...")
            val missing = checkModule(rootDir.name, rootDir)
            finish(missing, rootDir)
            return
        }

        logger.lifecycle("Index Checker: Scanning ${resolvedServiceNames.size} modules...")

        val allMissing = resolvedServiceNames.flatMap { moduleName ->
            val moduleDir = File(rootDir, moduleName)
            if (!moduleDir.exists()) {
                logger.warn("Module directory not found: $moduleName")
                emptyList()
            } else {
                checkModule(moduleName, moduleDir)
            }
        }

        finish(allMissing, rootDir)
    }

    private fun finish(allMissing: List<MissingIndex>, rootDir: File) {
        val baselineFile = resolveBaselineFile(rootDir)
        val shouldWriteBaseline = writeBaseline.orNull == true

        if (shouldWriteBaseline) {
            BaselineManager.writeBaseline(baselineFile, allMissing)
            logger.lifecycle("Index Checker: Baseline written to ${baselineFile.absolutePath} with ${allMissing.size} issue(s)")
            val comparison = BaselineManager.compare(allMissing, allMissing.map {
                BaselineIssue(it.serviceName, it.tableName, it.columnName)
            })
            ReportGenerator.generate(comparison, logger, htmlReportFile.get().asFile, jsonReportFile.get().asFile, warnOnExistingMissing.get())
            return
        }

        val baselineIssues = BaselineManager.readBaseline(baselineFile)
        val comparison = BaselineManager.compare(allMissing, baselineIssues)
        ReportGenerator.generate(comparison, logger, htmlReportFile.get().asFile, jsonReportFile.get().asFile, warnOnExistingMissing.get())

        if (failOnNewMissing.get() && comparison.newIssues.isNotEmpty()) {
            throw GradleException(
                "Index check failed: Found ${comparison.newIssues.size} new missing indexes not present in baseline"
            )
        }

        if (failOnMissing.get() && allMissing.isNotEmpty()) {
            throw GradleException(
                "Index check failed: Found ${allMissing.size} columns used in queries without indexes"
            )
        }
    }

    private fun resolveBaselineFile(rootDir: File): File {
        val configured = baselineFilePath.get().trim()
        val candidate = File(configured)
        return if (candidate.isAbsolute) candidate else File(rootDir, configured)
    }

    private fun checkModule(moduleName: String, moduleDir: File): List<MissingIndex> {
        val kotlinSrcDir = File(moduleDir, "src/main/kotlin")
        val entityDirNamesList = entityDirNames.get()
        val repoDirNamesList = repositoryDirNames.get()
        val lbPath = liquibaseRelativePath.get()

        val entityDirs = entityDirNamesList.flatMap { findDirectories(kotlinSrcDir, it) }
        val repoDirs = repoDirNamesList.flatMap { findDirectories(kotlinSrcDir, it) }
        val liquibaseDir = File(moduleDir, lbPath)

        if (entityDirs.isEmpty() || repoDirs.isEmpty() || !liquibaseDir.exists()) {
            logger.info("Skipping $moduleName (missing entity/repository/liquibase directories)")
            return emptyList()
        }

        // 1. Parse entities
        val entityMappings = entityDirs.fold(emptyMap<String, TableMapping>()) { acc, dir ->
            acc + EntityParser.parseEntities(dir)
        }

        if (entityMappings.isEmpty()) {
            logger.info("Skipping $moduleName (no entities found)")
            return emptyList()
        }

        logger.lifecycle("  $moduleName: Found ${entityMappings.size} entities")

        // 2. Parse repositories
        val queryColumns = repoDirs.flatMap { dir ->
            RepositoryParser.parseRepositories(dir, entityMappings)
        }
        logger.lifecycle("  $moduleName: Found ${queryColumns.size} column references in queries")

        // 3. Parse Liquibase indexes
        val indexedColumns = LiquibaseParser.parseIndexes(liquibaseDir)
        logger.lifecycle("  $moduleName: Found ${indexedColumns.size} indexed columns")

        // 4. Compare
        return DbIndexChecker.findMissingIndexes(
            serviceName = moduleName,
            queryColumns = queryColumns,
            indexedColumns = indexedColumns,
            excludeTables = excludeTables.get().toSet(),
            excludeColumns = excludeColumns.get().toSet(),
            excludeFindings = excludeFindings.get().toSet()
        )
    }

    private fun findDirectories(root: File, name: String): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isDirectory && it.name == name }
            .toList()
    }

    private fun discoverModules(rootDir: File): List<String> {
        val entityNames = entityDirNames.get().toSet()
        val repoNames = repositoryDirNames.get().toSet()
        val lbPath = liquibaseRelativePath.get()

        return rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "buildSrc" && it.name != "build" && it.name != "gradle" }
            ?.filter { moduleDir ->
                val kotlinSrc = File(moduleDir, "src/main/kotlin")
                val hasEntity = kotlinSrc.exists() && kotlinSrc.walkTopDown().any { it.isDirectory && it.name in entityNames }
                val hasRepo = kotlinSrc.exists() && kotlinSrc.walkTopDown().any { it.isDirectory && it.name in repoNames }
                val hasDb = File(moduleDir, lbPath).exists()
                hasEntity && hasRepo && hasDb
            }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }
}
