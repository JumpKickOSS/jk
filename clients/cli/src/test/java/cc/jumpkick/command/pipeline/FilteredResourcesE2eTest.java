// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A {@code [resources] filtered} root reaches the classpath expanded, and follows its values. */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class FilteredResourcesE2eTest {

    @Test
    void a_filtered_resource_is_read_expanded_at_runtime_and_follows_its_values(@TempDir Path tmp) throws Exception {
        Path project = project(tmp, "3391");
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", project.toString(), "--cache-dir", cache)).isEqualTo(0);
        assertThat(run("build", "-C", project.toString(), "--cache-dir", cache))
                .as("the test reads the expanded values off the classpath")
                .isEqualTo(0);
        Path copied = project.resolve("target/classes/info/remoting.properties");
        assertThat(copied).hasContent("""
                remoting=3391
                version=1.2.3
                literal=${kept}
                """);

        Files.writeString(project.resolve("jk.toml"), manifest("3392"));
        assertThat(run("build", "--skip-tests", "-C", project.toString(), "--cache-dir", cache))
                .isEqualTo(0);
        assertThat(Files.readString(copied)).contains("remoting=3392");

        Files.delete(project.resolve("src/filter/resources/info/remoting.properties"));
        assertThat(run("build", "--skip-tests", "-C", project.toString(), "--cache-dir", cache))
                .isEqualTo(0);
        assertThat(copied)
                .as("a filtered file deleted from its root leaves the classes")
                .doesNotExist();
    }

    private static String manifest(String remoting) {
        return """
                group   = "com.example"
                name    = "info"
                version = "1.2.3"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [resources]
                filtered = ["src/filter/resources"]

                [resources.properties]
                "remoting.version" = "%s"
                """.formatted(remoting);
    }

    private static Path project(Path tmp, String remoting) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("info"));
        Files.writeString(project.resolve("jk.toml"), manifest(remoting));
        Path res = Files.createDirectories(project.resolve("src/filter/resources/info"));
        Files.writeString(res.resolve("remoting.properties"), """
                remoting=${remoting.version}
                version=${project.version}
                literal=\\${kept}
                """);
        Path main = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(main.resolve("Info.java"), """
                package com.example;

                import java.io.IOException;
                import java.io.InputStream;
                import java.util.Properties;

                public final class Info {
                    public static Properties load() throws IOException {
                        Properties p = new Properties();
                        try (InputStream in = Info.class.getResourceAsStream("/info/remoting.properties")) {
                            p.load(in);
                        }
                        return p;
                    }
                }
                """);
        Path test = Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(test.resolve("InfoTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class InfoTest {
                    @Test
                    void reads_the_expanded_values() throws Exception {
                        assertEquals("3391", Info.load().getProperty("remoting"));
                        assertEquals("1.2.3", Info.load().getProperty("version"));
                    }
                }
                """);
        return project;
    }
}
