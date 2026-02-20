plugins {
    kotlin("jvm") version "2.3.10"
    `java-gradle-plugin`
    `maven-publish`
}

group = "cz.kubele.gradle"
version = "1.0.0"

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("indexChecker") {
            id = "cz.kubele.db-index-checker"
            implementationClass = "cz.kubele.gradle.dbindexchecker.IndexCheckerPlugin"
            displayName = "DB Index Checker"
            description = "Checks that all columns used in Spring Data repository queries have database indexes defined in Liquibase"
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