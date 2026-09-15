plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.example"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation(libs.picocli)
    implementation(libs.jackson.databind)
    implementation(libs.commons.text)
    implementation(libs.guava)
    implementation(libs.slf4j.simple)
}

tasks.withType<JavaCompile> { options.release.set(25) }

tasks.shadowJar {
    // Shadow 9 excludes duplicates before transformers see them; merged paths must opt back in.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "demo.Cli") }
}
