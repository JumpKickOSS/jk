// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for Groovy support in jk new / build / test wave 3). Mirrors
 * {@link KotlinCompilationTest}: the scaffolded sample sources are built for real (the Groovy
 * closure resolves from Maven Central via {@link SharedTestCache}), the freshness-stamp tests keep
 * an isolated per-test cache.
 */
@Tag("integration")
class GroovyCompilationTest {

    @Test
    void build_packages_scaffolded_groovy_classes_into_jar(@TempDir Path tempDir) throws IOException {
        run("new", "--group", "com.example", "--name", "widget", "--lang", "groovy", tempDir.toString());
        // Simple layout: package-less Calc.groovy at./src, CalcTest.groovy at./test/src.
        assertThat(tempDir.resolve("src/Calc.groovy")).exists();

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        Path jar = tempDir.resolve("target/lib/widget-0.1.0.jar");
        assertThat(jar).exists();
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getJarEntry("Calc.class")).isNotNull();
        }
    }

    @Test
    void second_build_skips_groovy_compile_when_nothing_changed(@TempDir Path tempDir) throws IOException {
        run("new", "--group", "com.example", "--name", "widget", "--lang", "groovy", tempDir.toString());
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Path gvClass = tempDir.resolve("target/classes/main/Calc.class");
        Path stamp = tempDir.resolve("target/classes/main/.gstamp");
        assertThat(stamp).exists(); // freshness stamp written
        assertThat(gvClass).exists();
        long firstMtime = Files.getLastModifiedTime(gvClass).toMillis();

        // A no-change rebuild must hit the freshness stamp and NOT re-invoke
        // groovyc — the output.class is left exactly as the first build wrote it.
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(gvClass).toMillis()).isEqualTo(firstMtime);
    }

    @Test
    void editing_a_groovy_source_recompiles(@TempDir Path tempDir) throws IOException {
        run("new", "--group", "com.example", "--name", "widget", "--lang", "groovy", tempDir.toString());
        Path src = tempDir.resolve("src/Calc.groovy");
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Path gvClass = tempDir.resolve("target/classes/main/Calc.class");
        long firstMtime = Files.getLastModifiedTime(gvClass).toMillis();

        // Edit the source forward in time so its mtime exceeds the stamp; the
        // next build must fall through the freshness check and recompile.
        Files.writeString(src, """
                class Calc {
                    int doubleValue(int value) {
                        value + value
                    }
                }
                """);
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(firstMtime + 5_000));

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(gvClass).toMillis()).isNotEqualTo(firstMtime);
    }

    @Test
    void test_runs_the_scaffolded_groovy_junit_test(@TempDir Path tempDir) throws IOException {
        run("new", "--group", "com.example", "--name", "widget", "--lang", "groovy", tempDir.toString());

        // The scaffolded CalcTest passes.
        assertThat(run("test", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg()))
                .isEqualTo(0);

        // Break the assertion: a nonzero exit proves the Groovy test actually executed.
        Path test = tempDir.resolve("test/src/CalcTest.groovy");
        assertThat(test).exists();
        Files.writeString(test, Files.readString(test).replace("assertEquals(10,", "assertEquals(11,"));
        assertThat(run("test", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg()))
                .isNotEqualTo(0);
    }

    @Test
    void mixed_groovy_and_java_resolve_both_directions(@TempDir Path tempDir) throws IOException {
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "mixed",
                "--lang",
                "groovy",
                "--layout",
                "traditional",
                tempDir.toString());
        // Opt into Java too — a mixed module declares both java and groovy.
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replace("[project]\n", "[project]\njava = 25\n"));
        // Groovy→Java: the Groovy class calls a Java helper (joint sweep).
        Path javaSrc = tempDir.resolve("src/main/java/com/example/Util.java");
        Files.createDirectories(javaSrc.getParent());
        Files.writeString(javaSrc, """
                package com.example;
                public final class Util {
                    private Util() {}
                    public static String decorate(String s) { return "<" + s + ">"; }
                }
                """);
        Path gvSrc = tempDir.resolve("src/main/groovy/com/example/Greeter.groovy");
        Files.createDirectories(gvSrc.getParent());
        Files.writeString(gvSrc, """
                package com.example

                class Greeter {
                    String greet(String name) { Util.decorate("hi " + name) }
                }
                """);
        // Java→Groovy: a Java class references the Groovy type (classes/stubs on javac's path).
        Path javaMain = tempDir.resolve("src/main/java/com/example/App.java");
        Files.writeString(javaMain, """
                package com.example;
                public final class App {
                    public static String run() { return new Greeter().greet("jk"); }
                }
                """);

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        try (JarFile jf =
                new JarFile(tempDir.resolve("target/lib/mixed-0.1.0.jar").toFile())) {
            assertThat(jf.getJarEntry("com/example/Util.class")).isNotNull();
            assertThat(jf.getJarEntry("com/example/Greeter.class")).isNotNull();
            assertThat(jf.getJarEntry("com/example/App.class")).isNotNull();
        }
    }

    @Test
    void spring_boot_groovy_module_packages_a_boot_jar(@TempDir Path tempDir) throws IOException {
        // Hand-wired Boot fixture: scaffold for the JDK pin, then declare the [spring-boot]
        // table + a versionless starter (the plugin auto-imports the Boot BOM) and replace the
        // sample sources with a Groovy @RestController application. Packaging assertion only
        // the app is never booted.
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "bootapp",
                "--lang",
                "groovy",
                "--layout",
                "traditional",
                tempDir.toString());
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml) + """

                        [application]
                        main = "com.example.Application"

                        [spring-boot]
                        version = "4.1.0"

                        [dependencies]
                        starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                        """);
        Path src = tempDir.resolve("src/main/groovy/com/example");
        Files.writeString(src.resolve("Application.groovy"), """
                package com.example

                import org.springframework.boot.SpringApplication
                import org.springframework.boot.autoconfigure.SpringBootApplication

                @SpringBootApplication
                class Application {
                    static void main(String[] args) {
                        SpringApplication.run(Application, args)
                    }
                }
                """);
        Files.writeString(src.resolve("HelloController.groovy"), """
                package com.example

                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RestController

                @RestController
                class HelloController {
                    @GetMapping('/')
                    String hello() { 'Hello from jk + Spring Boot + Groovy!' }
                }
                """);

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        // The boot-jar packager replaces the main artifact ([application] → target/, no lib/).
        Path jar = tempDir.resolve("target/bootapp-0.1.0.jar");
        assertThat(jar).exists();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Main-Class")).isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.Application");
            assertThat(jf.getEntry("BOOT-INF/classes/com/example/Application.class"))
                    .isNotNull();
            assertThat(jf.getEntry("BOOT-INF/classes/com/example/HelloController.class"))
                    .isNotNull();
            // The starters' runtime closure is nested under BOOT-INF/lib.
            assertThat(jf.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/lib/spring-")))
                    .as("spring libs nested in the boot jar")
                    .isTrue();
            // JK-1173: language runtime must nest so standalone java -jar can load Groovy classes.
            assertThat(jf.stream().anyMatch(e -> {
                        String n = e.getName();
                        return n.startsWith("BOOT-INF/lib/") && n.contains("groovy-");
                    }))
                    .as("groovy runtime nested in BOOT-INF/lib (lock-injected language runtime)")
                    .isTrue();
        }
        Path lock = tempDir.resolve("jk-lock.toml");
        if (Files.isRegularFile(lock)) {
            assertThat(Files.readString(lock)).contains("org.apache.groovy:groovy");
        }
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
