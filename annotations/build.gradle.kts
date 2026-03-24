plugins {
	kotlin("jvm") version "2.3.10"
	id("com.vanniktech.maven.publish") version "0.32.0"
}

group = "ee.kubele.gradle"
version = rootProject.version

repositories {
	mavenCentral()
}

kotlin {
	jvmToolchain(21)
}

mavenPublishing {
	publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
	signAllPublications()

	coordinates(group.toString(), "db-index-checker-annotations", version.toString())

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
