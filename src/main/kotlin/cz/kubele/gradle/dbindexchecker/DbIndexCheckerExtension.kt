package cz.kubele.gradle.dbindexchecker

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class DbIndexCheckerExtension {

    /** Fail the build if missing indexes are found. Default: false (warn only). */
    abstract val failOnMissing: Property<Boolean>

    /** Table names to exclude from checking. */
    abstract val excludeTables: ListProperty<String>

    /** Column names to always exclude (e.g., primary keys). */
    abstract val excludeColumns: ListProperty<String>

    /**
     * Subproject/module names to scan (monorepo mode).
     * Empty = auto-discover subprojects that have entity, repository, and Liquibase directories.
     * Not used in single-module mode — set [entityDirs], [repositoryDirs], [liquibaseDirs] instead.
     */
    abstract val serviceNames: ListProperty<String>

    /**
     * Directory name(s) containing JPA entity classes.
     * The plugin searches for directories with this name under src/main/kotlin.
     * Default: ["entity"]
     */
    abstract val entityDirNames: ListProperty<String>

    /**
     * Directory name(s) containing Spring Data repository interfaces.
     * Default: ["dao", "repository"]
     */
    abstract val repositoryDirNames: ListProperty<String>

    /**
     * Relative path to Liquibase changelog root directory (from module root).
     * Default: "src/main/resources/db"
     */
    abstract val liquibaseRelativePath: Property<String>

    init {
        failOnMissing.convention(false)
        excludeTables.convention(listOf("databasechangelog", "databasechangeloglock"))
        excludeColumns.convention(listOf("id"))
        serviceNames.convention(emptyList())
        entityDirNames.convention(listOf("entity"))
        repositoryDirNames.convention(listOf("dao", "repository"))
        liquibaseRelativePath.convention("src/main/resources/db")
    }
}
