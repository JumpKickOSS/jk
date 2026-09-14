plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.example"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    annotationProcessor(platform(libs.micronaut.platform))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    implementation(platform(libs.micronaut.platform))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("ch.qos.logback:logback-classic")
}

tasks.withType<JavaCompile> { options.release.set(25) }

tasks.shadowJar {
    // Shadow 9 excludes duplicates before transformers see them; merged paths must opt back in.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "demo.Application") }
}
