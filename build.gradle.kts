plugins {
	kotlin("jvm") version "2.3.10"
	id("com.gradle.plugin-publish") version "2.0.0"
}

group = "ee.kubele.gradle"
version = providers.gradleProperty("pluginVersion").getOrElse(
	try {
		providers.exec {
			commandLine("git", "describe", "--tags", "--exact-match", "--match", "v*")
		}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0-SNAPSHOT" }
	} catch (_: Exception) {
		"0.0.0-SNAPSHOT"
	}
)

repositories {
	mavenCentral()
}

gradlePlugin {
	website.set("https://github.com/mkubele/db-index-checker")
	vcsUrl.set("https://github.com/mkubele/db-index-checker")
	plugins {
		create("dbIndexChecker") {
			id = "ee.kubele.db-index-checker"
			implementationClass = "ee.kubele.gradle.dbindexchecker.DbIndexCheckerPlugin"
			displayName = "DB Index Checker"
			description =
				"Gradle plugin that analyzes Spring Data JPA repositories and Liquibase changelogs to report query columns missing database indexes."
			tags.set(listOf("spring-data", "liquibase", "database", "index", "jpa", "static-analysis"))
		}
	}
}

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(gradleApi())
	testImplementation(gradleTestKit())
	testImplementation(kotlin("test"))
}

tasks.test {
	useJUnitPlatform()
}
