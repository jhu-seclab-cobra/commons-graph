import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

val jvmVersion = libs.versions.jvm.get().toInt()

// Shared configuration for all library subprojects.
// Each submodule's build.gradle.kts only needs to declare its own dependencies.
subprojects {

    // ----- Plugins -----

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    group = "edu.jhu.cobra"
    version = "0.1.0"

    // ----- Repositories -----

    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }

    // ----- JVM Toolchain -----
    // jvmToolchain sets the JDK version, Kotlin jvmTarget, and Java
    // source/targetCompatibility all at once. The foojay-resolver plugin
    // (in settings.gradle.kts) auto-downloads the required JDK if missing.

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(jvmVersion)
    }

    // ----- Java Library -----

    configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    // ----- Testing -----
    // kotlin("test") auto-selects JUnit 4 as the test framework.

    dependencies {
        "testImplementation"(kotlin("test"))
    }

    // ----- Publishing -----

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") { from(components["java"]) }
        }
    }

    // ----- Code Quality -----

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }
}
