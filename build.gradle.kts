plugins {
    kotlin("jvm") version "2.3.10"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "cz.kubele.gradle"
version = "1.0.0"

repositories {
    mavenCentral()
}

gradlePlugin {
    website.set("https://github.com/mkubele/db-index-checker")
    vcsUrl.set("https://github.com/mkubele/db-index-checker")
    plugins {
        create("dbIndexChecker") {
            id = "cz.kubele.db-index-checker"
            implementationClass = "cz.kubele.gradle.dbindexchecker.DbIndexCheckerPlugin"
            displayName = "DB Index Checker"
            description = "Checks that all columns used in Spring Data repository queries have database indexes defined in Liquibase"
            tags.set(listOf("spring-data", "liquibase", "database", "index", "jpa", "static-analysis"))
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(gradleApi())
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
