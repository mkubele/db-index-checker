# DB Index Checker

A Gradle plugin that detects missing database indexes by analyzing Spring Data repository queries against Liquibase changelog definitions.

It parses JPA entity annotations, Spring Data repository interfaces (derived queries, JPQL, and native SQL), and Liquibase XML changelogs, then reports columns used in queries that lack corresponding database indexes.

## Setup

Apply the plugin in your `build.gradle.kts`:

```kotlin
plugins {
    id("cz.kubele.db-index-checker") version "1.0.0"
}
```

## Usage

```bash
./gradlew indexCheck
```

Reports are generated at:
- `build/reports/index-check/missing-indexes.html`
- `build/reports/index-check/missing-indexes.json`

## Configuration

```kotlin
dbIndexChecker {
    // Fail the build if missing indexes are found (default: false, warn only)
    failOnMissing.set(true)

    // Tables to exclude from checking (default: ["databasechangelog", "databasechangeloglock"])
    excludeTables.set(listOf("databasechangelog", "databasechangeloglock", "flyway_schema_history"))

    // Columns to always exclude (default: ["id"])
    excludeColumns.set(listOf("id"))

    // Module names to scan in a monorepo (default: [] = auto-discover)
    serviceNames.set(listOf("user-service", "order-service"))

    // Directory names containing JPA entities under src/main/kotlin (default: ["entity"])
    entityDirNames.set(listOf("entity", "model"))

    // Directory names containing Spring Data repositories (default: ["dao", "repository"])
    repositoryDirNames.set(listOf("dao", "repository"))

    // Path to Liquibase changelogs relative to module root (default: "src/main/resources/db")
    liquibaseRelativePath.set("src/main/resources/db")
}
```

## What It Detects

The plugin parses three sources and cross-references them:

**JPA Entities** — `@Entity`, `@Table`, `@Column`, `@JoinColumn` annotations. Handles Hibernate's default naming convention (camelCase to snake_case) for unannotated fields.

**Spring Data Repositories** — Three query styles:
- **Derived queries** — method names like `findByStatusAndCreatedAtBefore`
- **JPQL** — `@Query("SELECT e FROM Employee e WHERE e.department = :dept")`
- **Native SQL** — `@Query(value = "SELECT * FROM employees WHERE status = :s", nativeQuery = true)`

**Liquibase Changelogs** — `<createIndex>`, `<addUniqueConstraint>`, raw SQL `CREATE INDEX`, primary key definitions, and inline unique constraints. Follows `<include>` directives.

## Expected Project Structure

The plugin expects a standard Spring Boot / Liquibase layout:

```
my-service/
  src/main/kotlin/.../entity/     # JPA entities
  src/main/kotlin/.../repository/ # Spring Data repositories
  src/main/resources/db/          # Liquibase changelogs
    changelog.xml                 # Root changelog
```

For monorepos, the plugin auto-discovers submodules that contain all three directories, or you can specify them explicitly via `serviceNames`.

## Requirements

- Gradle 7.6.3+
- Java 21+
