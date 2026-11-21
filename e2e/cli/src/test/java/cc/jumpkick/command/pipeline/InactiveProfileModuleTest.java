// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.model.command.Exit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A module its reactor's {@code pom.xml} lists only in a profile Maven does not activate here is
 * one coexistence mode does not build: every verb entered in that directory refuses with the
 * engine's one line naming the profile and exits {@link Exit#CONFIG}, the way build and test do.
 */
@Tag("integration")
class InactiveProfileModuleTest {

    @BeforeAll
    static void materializeEngine() {
        EngineTestSupport.ensureEngineMaterialized();
    }

    private record Run(int exit, String err) {}

    private static Run run(String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Run(Jk.execute(args), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(orig);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"explain", "compile", "native", "image", "format"})
    void a_verb_in_a_module_only_an_inactive_profile_lists_exits_config_with_the_refusal(String verb, @TempDir Path tmp)
            throws Exception {
        Path root = reactor(tmp.resolve("reactor"));
        Path extra = root.resolve("extra");

        Run r = run("-C", extra.toString(), verb);

        assertThat(r.exit()).as(verb + " exit; stderr:\n" + r.err()).isEqualTo(Exit.CONFIG);
        assertThat(r.err()).contains("profile `extras`").contains("jk import pom.xml");
        assertThat(extra.resolve("jk.toml")).doesNotExist();
    }

    /** {@code core} at the top level, {@code extra} only in the {@code extras} profile; one class each. */
    private static Path reactor(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <modules>
                    <module>core</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>extras</id>
                      <modules>
                        <module>extra</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        for (String module : new String[] {"core", "extra"}) {
            Path dir = Files.createDirectories(root.resolve(module));
            Files.writeString(dir.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.example</groupId>
                        <artifactId>reactor</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(module));
            Files.writeString(
                    Files.createDirectories(dir.resolve("src/main/java/com/example"))
                            .resolve("Marker.java"),
                    """
                    package com.example;

                    public final class Marker {}
                    """);
        }
        return root;
    }
}
