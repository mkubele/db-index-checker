# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Gradle plugin (`ee.kubele.db-index-checker`) that validates database indexes for Spring Data repository queries against Liquibase changelog definitions. It parses JPA entities, Spring Data repositories (derived queries, JPQL, native SQL), and Liquibase XML/SQL changelogs, then reports columns used in queries that lack corresponding database indexes.

The project has two modules:
- **Root** — the Gradle plugin itself
- **annotations** — a lightweight `@SuppressIndexCheck` annotation artifact (`ee.kubele.gradle:db-index-checker-annotations`) with SOURCE retention, published separately to Maven Central

## Build Commands

```bash
./gradlew build          # Compile + test
./gradlew test           # Run tests (JUnit Platform)
./gradlew compileKotlin  # Compile only
./gradlew publishToMavenLocal  # Publish to local Maven repo
```

Run a single test class:
```bash
./gradlew test --tests "ee.kubele.gradle.dbindexchecker.RepositoryParserTest"
```

Run a single test method:
```bash
./gradlew test --tests "ee.kubele.gradle.dbindexchecker.RepositoryParserTest.methodName"
```

Kotlin 2.3.10, Java 21, Gradle 9.3.1.

## Architecture

The plugin registers a `dbIndexCheck` Gradle task (wired into `check`) that runs this pipeline:

1. **Module discovery** (`DbIndexCheckerTask`) — finds submodules with entity/repository/Liquibase dirs, or falls back to single-module scanning
2. **Entity parsing** (`EntityParser`) — regex-based extraction of `@Entity`, `@Table`, `@Column`, `@JoinColumn` annotations; maps fields to DB columns using Hibernate naming convention (camelCase → snake_case)
3. **Repository parsing** (`RepositoryParser`) — parses derived query method names (50+ condition suffixes), `@Query` JPQL (alias.field resolution), and native SQL (WHERE/JOIN ON/ORDER BY patterns); respects `@SuppressIndexCheck` annotations and comments
4. **Liquibase parsing** (`LiquibaseParser`) — XML DOM parsing of `<createIndex>`, `<addUniqueConstraint>`, raw SQL `CREATE INDEX`, primary keys, and inline constraints; follows `<include>`/`<includeAll>` directives; tracks composite index column positions
5. **Index checking** (`DbIndexChecker`) — compares query columns against indexed columns with deduplication
6. **Baseline comparison** (`BaselineManager`) — compares findings against a baseline JSON file, categorizing issues as new/existing/resolved
7. **Reporting** (`ReportGenerator`) — generates HTML (with IntelliJ IDE links) and JSON reports to `build/reports/index-check/`

### Key packages under `ee.kubele.gradle.dbindexchecker`

- `parser/` — EntityParser, RepositoryParser, LiquibaseParser (the bulk of the logic)
- `checker/` — DbIndexChecker (comparison + dedup)
- `model/` — Data classes: IndexedColumn, MissingIndex, QueryColumn, TableMapping, BaselineComparison
- `report/` — ReportGenerator (HTML + JSON), BaselineManager (baseline read/write/compare)
- Root — DbIndexCheckerPlugin (entry point), DbIndexCheckerTask (orchestration), DbIndexCheckerExtension (configuration), SuppressIndexCheck (annotation)

## Plugin Configuration

Extension name: `dbIndexChecker`. Key properties: `failOnMissing` (fail build on issues), `failOnNewMissing` (fail only on new issues vs baseline), `excludeTables`, `excludeColumns`, `excludeFindings` (table.column pairs), `serviceNames` (auto-discover if empty), `entityDirNames` (default: `["entity"]`), `repositoryDirNames` (default: `["dao", "repository"]`), `liquibaseRelativePath` (default: `"src/main/resources/db"`), `baselineFilePath` (default: `"db-index-checker-baseline.json"`).

## Notable Design Decisions

- Only the leftmost column (position 0) of composite indexes counts as "indexed" for query validation
- Entity parsing adds implicit base fields: `id`, `created_at`, `updated_at`
- All entity/column comparisons are case-insensitive (lowercase)
- No Spring dependency at runtime — the plugin only analyzes Spring Data source files via regex/text parsing
- Tests use GradleTestKit for functional tests and direct parser unit tests for logic
- Use idiomatic Kotlin language features
