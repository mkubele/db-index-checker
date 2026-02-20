package cz.kubele.gradle.dbindexchecker

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class DbIndexCheckerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("dbIndexChecker", DbIndexCheckerExtension::class.java)

        project.tasks.register("dbIndexCheck", DbIndexCheckerTask::class.java) { task ->
            task.group = "verification"
            task.description = "Check that all columns used in repository queries have database indexes"
            task.failOnMissing.set(extension.failOnMissing)
            task.failOnNewMissing.set(extension.failOnNewMissing)
            task.warnOnExistingMissing.set(extension.warnOnExistingMissing)
            task.baselineFilePath.set(extension.baselineFilePath)
            task.excludeTables.set(extension.excludeTables)
            task.excludeColumns.set(extension.excludeColumns)
            task.serviceNames.set(extension.serviceNames)
            task.entityDirNames.set(extension.entityDirNames)
            task.repositoryDirNames.set(extension.repositoryDirNames)
            task.liquibaseRelativePath.set(extension.liquibaseRelativePath)
            task.rootProjectDir.set(project.rootDir)
            task.htmlReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.html"))
            task.jsonReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.json"))

            task.kotlinSourceFiles.from(
                project.fileTree(project.rootDir) {
                    it.include("**/src/main/kotlin/**/*.kt")
                    it.exclude("**/build/**", "**/.gradle/**")
                }
            )
            task.liquibaseFiles.from(
                project.provider {
                    val lbPath = extension.liquibaseRelativePath.get()
                    project.fileTree(project.rootDir) {
                        it.include("**/$lbPath/**")
                        it.exclude("**/build/**", "**/.gradle/**")
                    }
                }
            )
            task.baselineInputFiles.from(
                project.provider {
                    val path = extension.baselineFilePath.get().trim()
                    val file = if (File(path).isAbsolute) File(path) else File(project.rootDir, path)
                    if (file.exists()) project.files(file) else project.files()
                }
            )
        }
    }
}
