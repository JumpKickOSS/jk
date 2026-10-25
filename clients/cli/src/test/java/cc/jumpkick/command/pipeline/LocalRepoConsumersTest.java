// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code jk install} and {@code jk snapshot} leave in a Maven local repository is something
 * Maven and Gradle builds beside jk can resolve: the files, their checksums, and a provenance hint
 * that does not send a build without jk's repository looking for them elsewhere.
 */
@Tag("slow")
@Tag("network")
class LocalRepoConsumersTest {

    private static final String LIB = "com.example:consumed-lib";

    /** {@code -C dir verb args…}: the directory goes before the verb, whose arguments a passthrough hands on. */
    private static int jk(Path dir, String verb, String... args) {
        String[] all = new String[args.length + 3];
        all[0] = "-C";
        all[1] = dir.toString();
        all[2] = verb;
        System.arraycopy(args, 0, all, 3, args.length);
        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = Jk.execute(all));
        if (exit[0] != 0) System.err.println("jk " + String.join(" ", all) + "\n" + streams.out() + streams.err());
        return exit[0];
    }

    private static void installLibrary(Path tmp, Path m2) throws IOException {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib.resolve("src/main/java/com/example"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "consumed-lib"
                version = "1.0.0"
                java    = 25
                """);
        Files.writeString(
                lib.resolve("src/main/java/com/example/Lib.java"),
                "package com.example; public final class Lib { public static String hi() { return \"hi\"; } }\n");
        assertThat(jk(lib, "snapshot", "--cache-dir", lib.resolve("cache").toString(), "--m2-dir", m2.toString()))
                .isEqualTo(0);
        assertThat(jk(lib, "install", "--cache-dir", lib.resolve("cache").toString(), "--m2-dir", m2.toString()))
                .isEqualTo(0);
    }

    private static Path mavenConsumer(Path tmp, String version) throws IOException {
        Path app = tmp.resolve("maven-" + version);
        Files.createDirectories(app);
        Files.writeString(app.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>consumer</artifactId><version>1</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>consumed-lib</artifactId><version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version));
        return app;
    }

    @Test
    void maven_resolves_the_release_and_the_snapshot_from_the_repository_jk_wrote(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        installLibrary(tmp, m2);
        for (String version : new String[] {"1.0.0", "1.0.0-SNAPSHOT"}) {
            Path app = mavenConsumer(tmp, version);
            int exit =
                    jk(app, "mvn", "-B", "-q", "-Dmaven.repo.local=" + m2.resolve("repository"), "dependency:resolve");
            assertThat(exit).as("Maven resolves %s:%s", LIB, version).isEqualTo(0);
        }
    }

    @Test
    void gradle_resolves_the_release_and_the_snapshot_from_maven_local(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        installLibrary(tmp, m2);
        for (String version : new String[] {"1.0.0", "1.0.0-SNAPSHOT"}) {
            Path app = tmp.resolve("gradle-" + version);
            Files.createDirectories(app);
            Files.writeString(app.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
            Files.writeString(app.resolve("build.gradle"), """
                    plugins { id 'java-library' }
                    repositories { mavenLocal() }
                    dependencies { implementation 'com.example:consumed-lib:%s' }
                    tasks.register('resolveLib') {
                        doLast { configurations.compileClasspath.resolve().each { println it.name } }
                    }
                    """.formatted(version));
            int exit = jk(app, "gradle", "-q", "-Dmaven.repo.local=" + m2.resolve("repository"), "resolveLib");
            assertThat(exit).as("Gradle resolves %s:%s", LIB, version).isEqualTo(0);
        }
    }
}
