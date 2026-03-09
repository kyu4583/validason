plugins {
  `java-library`
  `maven-publish`
  signing
  id("com.gradleup.nmcp.aggregation") version "1.4.4"
  id("com.gradleup.nmcp") version "1.4.4"
}

group = "io.github.kyu4583"
version = "1.0.2"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  withSourcesJar()
  withJavadocJar()
}

repositories {
  mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.release.set(17)
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifactId = "validason"

      pom {
        name.set("validason")
        description.set("High-performance JSON string validator focused on isValid checks.")
        url.set("https://github.com/kyu4583/validason")

        licenses {
          license {
            name.set("Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          }
        }

        developers {
          developer {
            id.set("kyu4583")
            name.set("Shin Sungkyu")
            email.set("kxu4583@naver.com")
          }
        }

        scm {
          url.set("https://github.com/kyu4583/validason")
          connection.set("scm:git:https://github.com/kyu4583/validason.git")
          developerConnection.set("scm:git:ssh://git@github.com/kyu4583/validason.git")
        }
      }
    }
  }
}

val requestedTasks = gradle.startParameter.taskNames.joinToString(" ").lowercase()
val centralPublishRequested = requestedTasks.contains("centralportal") || requestedTasks.contains("nmcp")

signing {
  if (centralPublishRequested) {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
  }
}

nmcpAggregation {
  centralPortal {
    val user = providers.gradleProperty("centralPortalUsername")
      .orElse(providers.gradleProperty("ossrhUsername"))
      .orNull
    val pass = providers.gradleProperty("centralPortalPassword")
      .orElse(providers.gradleProperty("ossrhPassword"))
      .orNull

    if (!user.isNullOrBlank()) {
      username = user
    }
    if (!pass.isNullOrBlank()) {
      password = pass
    }

    publishingType = "USER_MANAGED"
  }
}

dependencies {
  nmcpAggregation(project(":"))
}

