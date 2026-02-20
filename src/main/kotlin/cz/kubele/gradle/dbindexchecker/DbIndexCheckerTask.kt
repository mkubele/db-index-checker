package cz.kubele.gradle.dbindexchecker

import cz.kubele.gradle.dbindexchecker.checker.DbIndexChecker
import cz.kubele.gradle.dbindexchecker.model.MissingIndex
import cz.kubele.gradle.dbindexchecker.model.TableMapping
import cz.kubele.gradle.dbindexchecker.parser.EntityParser
import cz.kubele.gradle.dbindexchecker.parser.LiquibaseParser
import cz.kubele.gradle.dbindexchecker.parser.RepositoryParser
import cz.kubele.gradle.dbindexchecker.report.ReportGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class DbIndexCheckerTask : DefaultTask() {

    @get:Input
    abstract val failOnMissing: Property<Boolean>

    @get:Input
    abstract val excludeTables: ListProperty<String>

    @get:Input
    abstract val excludeColumns: ListProperty<String>

    @get:Input
    abstract val serviceNames: ListProperty<String>

    @get:Input
    abstract val entityDirNames: ListProperty<String>

    @get:Input
    abstract val repositoryDirNames: ListProperty<String>

    @get:Input
    abstract val liquibaseRelativePath: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rootProjectDir: DirectoryProperty

    @get:OutputFile
    abstract val htmlReportFile: RegularFileProperty

    @get:OutputFile
    abstract val jsonReportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val rootDir = rootProjectDir.get().asFile
        val resolvedServiceNames = serviceNames.get().ifEmpty { discoverModules(rootDir) }

        if (resolvedServiceNames.isEmpty()) {
            // Single-module project: scan the root itself
            logger.lifecycle("Index Checker: Scanning single-module project...")
            val missing = checkModule(rootDir.name, rootDir)
            finish(missing)
            return
        }

        logger.lifecycle("Index Checker: Scanning ${resolvedServiceNames.size} modules...")

        val allMissing = mutableListOf<MissingIndex>()
        for (moduleName in resolvedServiceNames) {
            val moduleDir = File(rootDir, moduleName)
            if (!moduleDir.exists()) {
                logger.warn("Module directory not found: $moduleName")
                continue
            }
            allMissing.addAll(checkModule(moduleName, moduleDir))
        }

        finish(allMissing)
    }

    private fun finish(allMissing: List<MissingIndex>) {
        ReportGenerator.generate(allMissing, logger, htmlReportFile.get().asFile, jsonReportFile.get().asFile)

        if (failOnMissing.get() && allMissing.isNotEmpty()) {
            throw GradleException(
                "Index check failed: Found ${allMissing.size} columns used in queries without indexes"
            )
        }
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
        val entityMappings = mutableMapOf<String, TableMapping>()
        entityDirs.forEach { dir -> entityMappings.putAll(EntityParser.parseEntities(dir)) }

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
            excludeColumns = excludeColumns.get().toSet()
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
