package cz.kubele.gradle.dbindexchecker

import org.gradle.api.Plugin
import org.gradle.api.Project

class DbIndexCheckerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("dbIndexChecker", DbIndexCheckerExtension::class.java)

        project.tasks.register("dbIndexCheck", DbIndexCheckerTask::class.java) { task ->
            task.group = "verification"
            task.description = "Check that all columns used in repository queries have database indexes"
            task.failOnMissing.set(extension.failOnMissing)
            task.excludeTables.set(extension.excludeTables)
            task.excludeColumns.set(extension.excludeColumns)
            task.serviceNames.set(extension.serviceNames)
            task.entityDirNames.set(extension.entityDirNames)
            task.repositoryDirNames.set(extension.repositoryDirNames)
            task.liquibaseRelativePath.set(extension.liquibaseRelativePath)
            task.rootProjectDir.set(project.rootDir)
            task.htmlReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.html"))
            task.jsonReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.json"))
        }
    }
}
