plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.example"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

tasks.withType<JavaCompile> { options.release.set(25) }

springBoot { mainClass.set("demo.Application") }

tasks.shadowJar {
    // Shadow 9 excludes duplicates before transformers see them; merged paths must opt back in.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesNotMatching(listOf("META-INF/services/**", "META-INF/spring.handlers", "META-INF/spring.schemas", "META-INF/spring.factories", "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    mergeServiceFiles()
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring.factories")
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    manifest { attributes("Main-Class" to "demo.Application") }
}
