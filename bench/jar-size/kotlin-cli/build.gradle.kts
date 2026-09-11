plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.example"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.okio)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
}

tasks.shadowJar {
    // Shadow 9 excludes duplicates before transformers see them; merged paths must opt back in.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "demo.MainKt") }
}
