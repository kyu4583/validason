plugins {
  java
  application
}

group = "local"
version = "0.1.0-SNAPSHOT"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(25))
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("com.fasterxml.jackson.core:jackson-core:2.20.2")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
  implementation("com.google.code.gson:gson:2.13.2")
  implementation("org.json:json:20250517")
  implementation("jakarta.json:jakarta.json-api:2.1.3")
  implementation("org.eclipse.parsson:parsson:1.1.7")
  implementation("com.squareup.moshi:moshi:1.15.2")
  implementation("com.dslplatform:dsl-json:2.0.2")
  implementation("com.jsoniter:jsoniter:0.9.23")
  implementation("com.networknt:json-schema-validator:1.5.9")
  implementation("org.everit.json:org.everit.json.schema:1.5.1")
}

application {
  mainClass.set("BenchmarkRunner")
}

tasks.named<JavaExec>("run") {
  val heap = (project.findProperty("runHeap") as String?) ?: "4g"
  val benchRuns = project.findProperty("runBenchRuns") as String?
  jvmArgs(
      "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
      "-Xms$heap",
      "-Xmx$heap"
  )
  if (!benchRuns.isNullOrBlank()) {
    jvmArgs("-Djsonvalidator.benchRuns=$benchRuns")
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.release.set(25)
}

tasks.register<JavaExec>("runSuiteChecker") {
  group = "application"
  description = "Run JsonSuiteChecker"
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("JsonSuiteChecker")
}

