import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

val publicationVersion = libs.versions.projectVersion.get()
val ktlintEngineVersion = libs.versions.ktlintEngine.get()

val minJavaProperty = providers.gradleProperty("minJava").get()
val minJava =
    minJavaProperty.toIntOrNull()?.takeIf { it >= 8 }
        ?: throw GradleException("minJava must be a Java version of 8 or later, got '$minJavaProperty'.")
val targetJavaVersion = JavaVersion.toVersion(minJava)
val runningJavaVersion = JavaVersion.current()
if (runningJavaVersion < targetJavaVersion) {
    throw GradleException(
        "minJava=$minJava requires JDK $minJava or later to run Gradle. " +
            "Gradle is running on JDK ${runningJavaVersion.majorVersion}.",
    )
}

// Shared configuration for all library subprojects.
// Each submodule's build.gradle.kts only needs to declare its own dependencies.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    group = "edu.jhu.cobra"
    version = publicationVersion

    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }

    // kotlin("test") auto-selects JUnit 4 as the test framework.
    dependencies {
        "testImplementation"(kotlin("test"))
    }

    configure<KotlinJvmProjectExtension> {
        explicitApi()
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
            freeCompilerArgs.add("-Xjdk-release=$minJava")
        }
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = targetJavaVersion
        targetCompatibility = targetJavaVersion
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(minJava)
    }

    tasks.withType<Test> {
        systemProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider")
        if (!project.hasProperty("includePerformanceTests")) {
            exclude("**/*PerformanceTest*")
            exclude("**/*BenchmarkTest*")
        } else {
            maxHeapSize = "8g"
            jvmArgs(
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=200",
                "-XX:G1HeapRegionSize=32m",
                "-XX:InitiatingHeapOccupancyPercent=45",
            )
            // Pass benchmark.impl system property for per-implementation isolation
            systemProperty("benchmark.impl", project.findProperty("benchmark.impl") ?: "")
            testLogging {
                showStandardStreams = true
            }
        }
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintEngineVersion)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") { from(components["java"]) }
        }
    }
}

// These implementation modules share core's internal completion record without exposing a public API.
configure(
    listOf(
        project(":jhu-seclab-cobra-commons-graph-impl-jgrapht"),
        project(":jhu-seclab-cobra-commons-graph-impl-mapdb"),
        project(":jhu-seclab-cobra-commons-graph-impl-neo4j"),
    ),
) {
    tasks.named<KotlinCompile>("compileKotlin") {
        val coreCompilation = project(":jhu-seclab-cobra-commons-graph").tasks.named<KotlinCompile>("compileKotlin")
        dependsOn(coreCompilation)
        friendPaths.from(coreCompilation.flatMap { it.destinationDirectory })
    }
}
