# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Gradle plugin (`cz.kubele.db-index-checker`) that validates database indexes for Spring Data repository queries against Liquibase changelog definitions. It parses JPA entities, Spring Data repositories (derived queries, JPQL, native SQL), and Liquibase XML changelogs, then reports columns used in queries that lack corresponding database indexes.

## Build Commands

```bash
./gradlew build          # Compile + test
./gradlew test           # Run tests (JUnit Platform)
./gradlew compileKotlin  # Compile only
./gradlew publishToMavenLocal  # Publish to local Maven repo
```

Kotlin 2.3.10, Java 21, Gradle 9.3.1.

## Architecture

The plugin registers an `indexCheck` Gradle task that runs this pipeline:

1. **Module discovery** (`IndexCheckerTask`) — finds submodules with entity/repository/Liquibase dirs, or falls back to single-module
2. **Entity parsing** (`EntityParser`) — regex-based extraction of `@Entity`, `@Table`, `@Column`, `@JoinColumn` annotations; maps fields to DB columns using Hibernate naming convention (camelCase → snake_case)
3. **Repository parsing** (`RepositoryParser`) — parses derived query method names (50+ condition suffixes), `@Query` JPQL (alias.field resolution), and native SQL (WHERE/JOIN ON/ORDER BY patterns)
4. **Liquibase parsing** (`LiquibaseParser`) — XML DOM parsing of `<createIndex>`, `<addUniqueConstraint>`, raw SQL `CREATE INDEX`, primary keys, and inline constraints; tracks composite index column positions
5. **Index checking** (`IndexChecker`) — compares query columns against indexed columns with deduplication
6. **Reporting** (`ReportGenerator`) — generates HTML (with IntelliJ IDE links) and JSON reports to `build/reports/index-check/`

### Key packages under `cz.kubele.gradle.indexchecker`

- `parser/` — EntityParser, RepositoryParser, LiquibaseParser (the bulk of the logic, ~1080 lines)
- `checker/` — IndexChecker (comparison + dedup)
- `model/` — Data classes: IndexedColumn, MissingIndex, QueryColumn, TableMapping
- `report/` — ReportGenerator (HTML + JSON output)
- Root — IndexCheckerPlugin (entry point), IndexCheckerTask (orchestration), IndexCheckerExtension (configuration)

## Plugin Configuration

Extension name: `indexChecker`. Key properties: `failOnMissing` (fail build on issues), `excludeTables`, `excludeColumns`, `serviceNames` (auto-discover if empty), `entityDirNames` (default: `["entity"]`), `repositoryDirNames` (default: `["dao", "repository"]`), `liquibaseRelativePath` (default: `"src/main/resources/db"`).

## Notable Design Decisions

- Only the leftmost column (position 0) of composite indexes counts as "indexed" for query validation
- Entity parsing adds implicit base fields: `id`, `created_at`, `updated_at`
- All entity/column comparisons are case-insensitive (lowercase)
- No Spring dependency at runtime — the plugin only analyzes Spring Data source files via regex/text parsing
- Use idiomatic Kotlin language features.