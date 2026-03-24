plugins {
	kotlin("jvm") version "2.3.10"
	`maven-publish`
	signing
}

group = "ee.kubele.gradle"
version = rootProject.version

repositories {
	mavenCentral()
}

kotlin {
	jvmToolchain(21)
}

java {
	withSourcesJar()
	withJavadocJar()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			artifactId = "db-index-checker-annotations"
			from(components["java"])

			pom {
				name.set("DB Index Checker Annotations")
				description.set("Annotations for the db-index-checker Gradle plugin")
				url.set("https://github.com/mkubele/db-index-checker")
				licenses {
					license {
						name.set("The Apache License, Version 2.0")
						url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
					}
				}
				developers {
					developer {
						id.set("mkubele")
						name.set("Michal Kubele")
					}
				}
				scm {
					connection.set("scm:git:git://github.com/mkubele/db-index-checker.git")
					developerConnection.set("scm:git:ssh://github.com/mkubele/db-index-checker.git")
					url.set("https://github.com/mkubele/db-index-checker")
				}
			}
		}
	}
	repositories {
		maven {
			name = "central"
			url = uri("https://central.sonatype.com/api/v1/publisher/deployments/download/")
			credentials {
				username = providers.gradleProperty("sonatypeUsername").orNull
					?: System.getenv("SONATYPE_USERNAME") ?: ""
				password = providers.gradleProperty("sonatypePassword").orNull
					?: System.getenv("SONATYPE_PASSWORD") ?: ""
			}
		}
	}
}

signing {
	val signingKey = providers.gradleProperty("signing.key").orNull ?: System.getenv("GPG_SIGNING_KEY")
	val signingPassword = providers.gradleProperty("signing.password").orNull ?: System.getenv("GPG_SIGNING_PASSWORD")
	if (signingKey != null && signingPassword != null) {
		useInMemoryPgpKeys(signingKey, signingPassword)
	}
	sign(publishing.publications["maven"])
	isRequired = signingKey != null
}