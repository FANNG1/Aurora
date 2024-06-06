/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
import com.github.gradle.node.NodeExtension
import com.github.gradle.node.NodePlugin
import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.ReportRenderer
import com.github.vlsi.gradle.dsl.configureEach
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.support.serviceOf
import java.io.File
import java.io.IOException
import java.util.Locale

plugins {
  `maven-publish`
  id("java")
  id("idea")
  id("jacoco")
  alias(libs.plugins.gradle.extensions)
  alias(libs.plugins.node) apply false

  // Spotless version < 6.19.0 (https://github.com/diffplug/spotless/issues/1819) has an issue
  // running against JDK21, but we cannot upgrade the spotless to 6.19.0 or later since it only
  // support JDK11+. So we don't support JDK21 and thrown an exception for now.
  if (JavaVersion.current() >= JavaVersion.VERSION_1_8 &&
    JavaVersion.current() <= JavaVersion.VERSION_17
  ) {
    alias(libs.plugins.spotless)
  } else {
    throw GradleException(
      "Gravitino Gradle toolchain current doesn't support " +
        "Java version: ${JavaVersion.current()}. Please use JDK8 to 17."
    )
  }

  alias(libs.plugins.publish)
  // Apply one top level rat plugin to perform any required license enforcement analysis
  alias(libs.plugins.rat)
  alias(libs.plugins.bom)
  alias(libs.plugins.dependencyLicenseReport)
  alias(libs.plugins.tasktree)
  alias(libs.plugins.errorprone)
}

if (extra["jdkVersion"] !in listOf("8", "11", "17")) {
  throw GradleException(
    "Gravitino current doesn't support building with " +
      "Java version: ${extra["jdkVersion"]}. Please use JDK8, 11 or 17."
  )
}

project.extra["extraJvmArgs"] = if (extra["jdkVersion"] in listOf("8", "11")) {
  listOf()
} else {
  listOf(
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.math=ALL-UNNAMED",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    "--add-opens", "java.base/java.text=ALL-UNNAMED",
    "--add-opens", "java.base/java.time=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens", "java.base/java.util.regex=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.reflect=ALL-UNNAMED",
    "--add-opens", "java.sql/java.sql=ALL-UNNAMED",
    "--add-opens", "java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens", "java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens", "java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens", "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED"
  )
}

licenseReport {
  renderers = arrayOf<ReportRenderer>(InventoryHtmlReportRenderer("report.html", "Backend"))
  filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
}
repositories { mavenCentral() }

allprojects {
  apply(plugin = "com.diffplug.spotless")
  repositories {
    mavenCentral()
    mavenLocal()
  }

  plugins.withType<com.diffplug.gradle.spotless.SpotlessPlugin>().configureEach {
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
      java {
        // Fix the Google Java Format version to 1.7. Since JDK8 only support Google Java Format
        // 1.7, which is not compatible with JDK17. We will use a newer version when we upgrade to
        // JDK17.
        googleJavaFormat("1.7")
        removeUnusedImports()
        trimTrailingWhitespace()
        replaceRegex(
          "Remove wildcard imports",
          "import\\s+[^\\*\\s]+\\*;(\\r\\n|\\r|\\n)",
          "$1"
        )
        replaceRegex(
          "Remove static wildcard imports",
          "import\\s+(?:static\\s+)?[^*\\s]+\\*;(\\r\\n|\\r|\\n)",
          "$1"
        )

        targetExclude("**/build/**")
      }

      kotlinGradle {
        target("*.gradle.kts")
        ktlint().editorConfigOverride(mapOf("indent_size" to 2))
      }
    }
  }
}

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
      snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))

      val sonatypeUser =
        System.getenv("SONATYPE_USER").takeUnless { it.isNullOrEmpty() }
          ?: extra["SONATYPE_USER"].toString()
      val sonatypePassword =
        System.getenv("SONATYPE_PASSWORD").takeUnless { it.isNullOrEmpty() }
          ?: extra["SONATYPE_PASSWORD"].toString()

      username.set(sonatypeUser)
      password.set(sonatypePassword)
    }
  }

  packageGroup.set("com.datastrato.gravitino")
}

subprojects {

  apply(plugin = "jacoco")
  apply(plugin = "maven-publish")
  apply(plugin = "java")

  repositories {
    mavenCentral()
    mavenLocal()
  }

  java {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(extra["jdkVersion"].toString().toInt()))
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }
  }

  gradle.projectsEvaluated {
    tasks.withType<JavaCompile> {
      options.compilerArgs.addAll(
        arrayOf(
          "-Xlint:cast",
          "-Xlint:deprecation",
          "-Xlint:divzero",
          "-Xlint:empty",
          "-Xlint:fallthrough",
          "-Xlint:finally",
          "-Xlint:overrides",
          "-Xlint:static",
          "-Werror"
        )
      )
    }
  }

  if (project.name != "meta") {
    apply(plugin = "net.ltgt.errorprone")
    dependencies {
      errorprone("com.google.errorprone:error_prone_core:2.10.0")
    }

    tasks.withType<JavaCompile>().configureEach {
      options.errorprone.isEnabled.set(true)
      options.errorprone.disableWarningsInGeneratedCode.set(true)
      options.errorprone.disable(
        "AlmostJavadoc",
        "CanonicalDuration",
        "CheckReturnValue",
        "ComparableType",
        "ConstantOverflow",
        "DoubleBraceInitialization",
        "EqualsUnsafeCast",
        "EmptyBlockTag",
        "FutureReturnValueIgnored",
        "InconsistentCapitalization",
        "InconsistentHashCode",
        "JavaTimeDefaultTimeZone",
        "JdkObsolete",
        "LockNotBeforeTry",
        "MissingSummary",
        "MissingOverride",
        "MutableConstantField",
        "NonOverridingEquals",
        "ObjectEqualsForPrimitives",
        "OperatorPrecedence",
        "ReturnValueIgnored",
        "SameNameButDifferent",
        "StaticAssignmentInConstructor",
        "StringSplitter",
        "ThreadPriorityCheck",
        "ThrowIfUncheckedKnownChecked",
        "TypeParameterUnusedInFormals",
        "UnicodeEscape",
        "UnnecessaryParentheses",
        "UnsafeReflectiveConstructionCast",
        "UnusedMethod",
        "VariableNameSameAsType",
        "WaitNotInLoop"
      )
    }
  }

  tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    options.locale = "en_US"
  }

  val sourcesJar by tasks.registering(Jar::class) {
    from(sourceSets.named("main").get().allSource)
    archiveClassifier.set("sources")
  }

  val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks["javadoc"])
  }

  tasks.configureEach<Test> {
    testLogging {
      exceptionFormat = TestExceptionFormat.FULL
      showExceptions = true
      showCauses = true
      showStackTraces = true
    }
    reports.html.outputLocation.set(file("${rootProject.projectDir}/build/reports/"))
    val skipTests = project.hasProperty("skipTests")
    if (!skipTests) {
      jvmArgs = listOf("-Xmx2G")
      useJUnitPlatform()
      jvmArgs(project.property("extraJvmArgs") as List<*>)
      finalizedBy(tasks.getByName("jacocoTestReport"))
    }
  }

  tasks.withType<JacocoReport> {
    reports {
      csv.required.set(true)
      xml.required.set(true)
      html.required.set(true)
    }
  }

  tasks.register("allDeps", DependencyReportTask::class)

  group = "com.datastrato.aurora"
  version = "$version"

  tasks.withType<Jar> {
    archiveBaseName.set("${rootProject.name.lowercase(Locale.getDefault())}-${project.name}")
    if (project.name == "server") {
      from(sourceSets.main.get().resources)
      setDuplicatesStrategy(DuplicatesStrategy.INCLUDE)
    }

    exclude("log4j2.properties")
    exclude("test/**")
  }
}

tasks.rat {
  substringMatcher("DS", "Datastrato", "Copyright 2023 Datastrato Pvt Ltd.")
  substringMatcher("DS", "Datastrato", "Copyright 2024 Datastrato Pvt Ltd.")
  approvedLicense("Datastrato")
  approvedLicense("Apache License Version 2.0")

  // Set input directory to that of the root project instead of the CWD. This
  // makes .gitignore rules (added below) work properly.
  inputDir.set(project.rootDir)

  val exclusions = mutableListOf(
    // Ignore files we track but do not need headers
    "**/.github/**/*",
    "dev/docker/**/*.xml",
    "dev/docker/**/*.conf",
    "dev/docker/kerberos-hive/kadm5.acl",
    "**/*.log",
    "**/licenses/*.txt",
    "**/licenses/*.md",
    "integration-test/**",
    "web/.**",
    "web/next-env.d.ts",
    "web/dist/**/*",
    "web/node_modules/**/*",
    "web/src/lib/utils/axios/**/*",
    "web/src/lib/enums/httpEnum.ts",
    "web/src/types/axios.d.ts",
    "web/yarn.lock",
    "web/package-lock.json",
    "web/pnpm-lock.yaml",
    "web/src/lib/icons/svg/**/*.svg",
    "**/LICENSE.*",
    "**/NOTICE.*",
    "ROADMAP.md"
  )

  // Add .gitignore excludes to the Apache Rat exclusion list.
  val gitIgnore = project(":").file(".gitignore")
  if (gitIgnore.exists()) {
    val gitIgnoreExcludes = gitIgnore.readLines().filter {
      it.isNotEmpty() && !it.startsWith("#")
    }
    exclusions.addAll(gitIgnoreExcludes)
  }

  verbose.set(true)
  failOnError.set(true)
  setExcludes(exclusions)
}

tasks.check.get().dependsOn(tasks.rat)

tasks.cyclonedxBom {
  setIncludeConfigs(listOf("runtimeClasspath"))
}

jacoco {
  toolVersion = "0.8.10"
  reportsDirectory.set(layout.buildDirectory.dir("JacocoReport"))
}

apply(plugin = "com.dorongold.task-tree")

/*
project.extra["docker_it_test"] = false
project.extra["dockerRunning"] = false
project.extra["macDockerConnector"] = false
project.extra["isOrbStack"] = false

// The following is to check the docker status and print the tip message
fun printDockerCheckInfo() {
  checkMacDockerConnector()
  checkDockerStatus()
  checkOrbStackStatus()

  val testMode = project.properties["testMode"] as? String ?: "embedded"
  if (testMode != "deploy" && testMode != "embedded") {
    return
  }
  val dockerRunning = project.extra["dockerRunning"] as? Boolean ?: false
  val macDockerConnector = project.extra["macDockerConnector"] as? Boolean ?: false
  val isOrbStack = project.extra["isOrbStack"] as? Boolean ?: false

  if (OperatingSystem.current().isMacOsX() &&
    dockerRunning &&
    (macDockerConnector || isOrbStack)
  ) {
    project.extra["docker_it_test"] = true
  } else if (OperatingSystem.current().isLinux() && dockerRunning) {
    project.extra["docker_it_test"] = true
  }

  println("------------------ Check Docker environment ---------------------")
  println("Docker server status ............................................ [${if (dockerRunning) "running" else "stop"}]")
  if (OperatingSystem.current().isMacOsX()) {
    println("mac-docker-connector status ..................................... [${if (macDockerConnector) "running" else "stop"}]")
    println("OrbStack status ................................................. [${if (dockerRunning && isOrbStack) "yes" else "no"}]")
  }

  val docker_it_test = project.extra["docker_it_test"] as? Boolean ?: false
  if (!docker_it_test) {
    println("Run test cases without `gravitino-docker-it` tag ................ [$testMode test]")
  } else {
    println("Using Gravitino IT Docker container to run all integration tests. [$testMode test]")
  }
  println("-----------------------------------------------------------------")

  // Print help message if Docker server or mac-docker-connector is not running
  printDockerServerTip()
  printMacDockerTip()
}

fun printDockerServerTip() {
  val dockerRunning = project.extra["dockerRunning"] as? Boolean ?: false
  if (!dockerRunning) {
    val redColor = "\u001B[31m"
    val resetColor = "\u001B[0m"
    println("Tip: Please make sure to start the ${redColor}Docker server$resetColor before running the integration tests.")
  }
}

fun printMacDockerTip() {
  val macDockerConnector = project.extra["macDockerConnector"] as? Boolean ?: false
  val isOrbStack = project.extra["isOrbStack"] as? Boolean ?: false
  if (OperatingSystem.current().isMacOsX() && !macDockerConnector && !isOrbStack) {
    val redColor = "\u001B[31m"
    val resetColor = "\u001B[0m"
    println(
      "Tip: Please make sure to use ${redColor}OrbStack$resetColor or execute the " +
        "$redColor`dev/docker/tools/mac-docker-connector.sh`$resetColor script before running" +
        " the integration test on macOS."
    )
  }
}

fun checkMacDockerConnector() {
  if (!OperatingSystem.current().isMacOsX()) {
    // Only MacOs requires the use of `docker-connector`
    return
  }

  try {
    val processName = "docker-connector"
    val command = "pgrep -x -q $processName"

    val execResult = project.exec {
      commandLine("bash", "-c", command)
    }
    if (execResult.exitValue == 0) {
      project.extra["macDockerConnector"] = true
    }
  } catch (e: Exception) {
    println("checkContainerRunning command execution failed: ${e.message}")
  }
}

fun checkDockerStatus() {
  try {
    val process = ProcessBuilder("docker", "info").start()
    val exitCode = process.waitFor()

    if (exitCode == 0) {
      project.extra["dockerRunning"] = true
    } else {
      println("checkDockerStatus command execution failed with exit code $exitCode")
    }
  } catch (e: IOException) {
    println("checkDockerStatus command execution failed: ${e.message}")
  }
}

fun checkOrbStackStatus() {
  if (!OperatingSystem.current().isMacOsX()) {
    return
  }

  try {
    val process = ProcessBuilder("docker", "context", "show").start()
    val exitCode = process.waitFor()
    if (exitCode == 0) {
      val currentContext = process.inputStream.bufferedReader().readText()
      println("Current docker context is: $currentContext")
      project.extra["isOrbStack"] = currentContext.lowercase(Locale.getDefault()).contains("orbstack")
    } else {
      println("checkOrbStackStatus Command execution failed with exit code $exitCode")
    }
  } catch (e: IOException) {
    println("checkOrbStackStatus command execution failed: ${e.message}")
  }
}

printDockerCheckInfo()
*/