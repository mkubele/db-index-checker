# DB Index Checker

A Gradle plugin that detects missing database indexes by analyzing Spring Data repository queries against Liquibase changelog definitions.

It parses JPA entity annotations, Spring Data repository interfaces (derived queries, JPQL, and native SQL), and Liquibase changelogs (XML and formatted SQL), then reports columns used in queries that lack corresponding database indexes.

## Setup

Apply the plugin in your `build.gradle.kts`:

```kotlin
plugins {
    id("cz.kubele.db-index-checker") version "1.0.0"
}
```

## Usage

```bash
./gradlew dbIndexCheck
```

Reports are generated at:
- `build/reports/index-check/missing-indexes.html`
- `build/reports/index-check/missing-indexes.json`

Create/update baseline file:
```bash
./gradlew dbIndexCheck --write-baseline
```

## Configuration

```kotlin
dbIndexChecker {
    // Fail the build if missing indexes are found (default: false, warn only)
    failOnMissing.set(true)

    // Fail only on new issues compared to baseline (default: false)
    failOnNewMissing.set(true)

    // Warn for issues already listed in baseline (default: true)
    warnOnExistingMissing.set(true)

    // Baseline JSON path relative to root project (default: "db-index-checker-baseline.json")
    baselineFilePath.set("db-index-checker-baseline.json")

    // Tables to exclude from checking (default: ["databasechangelog", "databasechangeloglock"])
    excludeTables.set(listOf("databasechangelog", "databasechangeloglock", "flyway_schema_history"))

    // Columns to always exclude (default: ["id"])
    excludeColumns.set(listOf("id"))

    // Per-finding exclusions as "table.column" pairs (default: [])
    excludeFindings.set(listOf("users.status", "orders.total_amount"))

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

When baseline exists, report output is split into `new`, `existing`, and `resolved` issues.

## Suppressing Findings

### In source code

Add `@SuppressIndexCheck` in a comment above a repository method to suppress its findings:

```kotlin
// @SuppressIndexCheck
fun findByStatus(status: String): List<User>

// @SuppressIndexCheck("status", "email_address")  — suppress specific columns only
fun findByStatusAndEmailAndName(status: String, email: String, name: String): List<User>
```

Without arguments, all columns from that method are suppressed. With column names, only those specific columns are suppressed. The suppression applies only to the immediately following method.

### In configuration

Use `excludeFindings` for table+column pair exclusions without modifying source code:

```kotlin
dbIndexChecker {
    excludeFindings.set(listOf("users.status", "orders.total_amount"))
}
```

This is useful when you intentionally query without an index (e.g., low-cardinality columns, small tables) and don't want to annotate every call site.

## What It Detects

The plugin parses three sources and cross-references them:

**JPA Entities** — `@Entity`, `@Table`, `@Column`, `@JoinColumn` annotations. Handles Hibernate's default naming convention (camelCase to snake_case) for unannotated fields.

**Spring Data Repositories** — Three query styles:
- **Derived queries** — method names like `findByStatusAndCreatedAtBefore`
- **JPQL** — `@Query("SELECT e FROM Employee e WHERE e.department = :dept")`
- **Native SQL** — `@Query(value = "SELECT * FROM employees WHERE status = :s", nativeQuery = true)`

**Liquibase Changelogs** — XML and formatted SQL changelogs, including `<createIndex>`, `<addUniqueConstraint>`, `<sqlFile>`, raw SQL `CREATE INDEX`, primary key definitions, and inline unique constraints. Follows `<include>`, `<includeAll>`, and SQL `--include file:` directives.

## Expected Project Structure

The plugin expects a standard Spring Boot / Liquibase layout:

```
my-service/
  src/main/kotlin/.../entity/     # JPA entities
  src/main/kotlin/.../repository/ # Spring Data repositories
  src/main/resources/db/          # Liquibase changelogs
    changelog.xml                 # Root changelog (or changelog.sql)
```

For monorepos, the plugin auto-discovers submodules that contain all three directories, or you can specify them explicitly via `serviceNames`.

## Requirements

- Gradle 7.6.3+
- Java 21+
