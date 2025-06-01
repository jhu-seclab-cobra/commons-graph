import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.kover)
    `java-library`
    `maven-publish`
}

group = "edu.jhu.cobra"
version = "0.1.0"

val sourceJavaVersion = JavaVersion.toVersion(libs.versions.javaSource.get())
val targetJavaVersion = JavaVersion.toVersion(libs.versions.javaTarget.get())

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    api(libs.cobra.commons.value)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines)
}

kotlin {
    jvmToolchain { languageVersion.set(JavaLanguageVersion.of(sourceJavaVersion.majorVersion)) }
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(targetJavaVersion.toString()) }
}

java {
    sourceCompatibility = sourceJavaVersion
    targetCompatibility = targetJavaVersion
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}

// Enables experimental Kotlin feature: context receivers.
// This feature is experimental and may affect framework adaptability.
// We will move to the context-parameters in the future.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { freeCompilerArgs.add("-Xcontext-receivers") }
}
