// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Keep in lock-step with jk's own default javac flags (cc.jumpkick.compile.JavacDefaults),
    // so `gradle build` and `jk build` surface the same javac warnings.
    options.compilerArgs.add("-Xlint:deprecation,unchecked")
}

// Gradle's java-test-fixtures plugin defaults to src/testFixtures/java. jk's fixtures live in
// src/fixtures/java — no camelCase directory names.
pluginManager.withPlugin("java-test-fixtures") {
    sourceSets.named("testFixtures") { java.setSrcDirs(listOf("src/fixtures/java")) }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "testCompileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "compileOnly"(libs.findLibrary("lombok").orElseThrow())
    "annotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testCompileOnly"(libs.findLibrary("lombok").orElseThrow())
    "testAnnotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testImplementation"(libs.findLibrary("junit-jupiter").orElseThrow())
    "testImplementation"(libs.findLibrary("assertj-core").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

val treeLocalRepoName = "treeLocal"

pluginManager.withPlugin("maven-publish") {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = treeLocalRepoName
                url = uri(rootProject.layout.buildDirectory.dir("local-maven-repo"))
            }
        }
    }
}
