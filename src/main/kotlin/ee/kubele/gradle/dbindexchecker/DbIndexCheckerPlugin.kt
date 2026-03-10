package ee.kubele.gradle.dbindexchecker

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

@Suppress("unused")
class DbIndexCheckerPlugin : Plugin<Project> {
	override fun apply(project: Project) {
		val extension = project.extensions.create("dbIndexChecker", DbIndexCheckerExtension::class.java)

		// Root task — scans all modules (existing behavior)
		val dbIndexCheckTask = project.tasks.register("dbIndexCheck", DbIndexCheckerTask::class.java) { task ->
			configureTask(task, extension, project.rootDir, project)
			task.serviceNames.set(extension.serviceNames)
		}

		// Per-subproject tasks for ./gradlew :my-service:dbIndexCheck
		project.subprojects { subproject ->
			subproject.tasks.register("dbIndexCheck", DbIndexCheckerTask::class.java) { task ->
				configureTask(task, extension, subproject.projectDir, subproject)
				task.serviceNames.set(emptyList<String>()) // single-module mode
			}
		}

		project.pluginManager.withPlugin("base") {
			project.tasks.named("check") {
				it.dependsOn(dbIndexCheckTask)
			}
		}
	}

	private fun configureTask(
		task: DbIndexCheckerTask,
		extension: DbIndexCheckerExtension,
		scanDir: File,
		project: Project
	) {
		task.group = "verification"
		task.description = "Check that all columns used in repository queries have database indexes"
		task.failOnMissing.set(extension.failOnMissing)
		task.failOnNewMissing.set(extension.failOnNewMissing)
		task.warnOnExistingMissing.set(extension.warnOnExistingMissing)
		val rootDir = project.rootProject.rootDir
		task.baselineFilePath.set(extension.baselineFilePath.map { path ->
			val f = File(path.trim())
			if (f.isAbsolute) f.absolutePath else File(rootDir, path.trim()).absolutePath
		})
		task.excludeTables.set(extension.excludeTables)
		task.excludeColumns.set(extension.excludeColumns)
		task.excludeFindings.set(extension.excludeFindings)
		task.entityDirNames.set(extension.entityDirNames)
		task.repositoryDirNames.set(extension.repositoryDirNames)
		task.liquibaseRelativePath.set(extension.liquibaseRelativePath)
		task.rootProjectDir.set(scanDir)
		task.htmlReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.html"))
		task.jsonReportFile.set(project.layout.buildDirectory.file("reports/index-check/missing-indexes.json"))
		task.kotlinSourceFiles.from(
			project.fileTree(scanDir) {
				it.include("**/src/main/kotlin/**/*.kt")
				it.exclude("**/build/**", "**/.gradle/**")
			}
		)
		task.liquibaseFiles.from(
			project.provider {
				project.fileTree(scanDir) {
					it.include("**/${extension.liquibaseRelativePath.get()}/**")
					it.exclude("**/build/**", "**/.gradle/**")
				}
			}
		)
		task.baselineInputFiles.from(
			project.provider {
				val path = extension.baselineFilePath.get().trim()
				val file = File(path).let { if (it.isAbsolute) it else File(rootDir, path) }
				if (file.exists()) project.files(file) else project.files()
			}
		)
	}
}
