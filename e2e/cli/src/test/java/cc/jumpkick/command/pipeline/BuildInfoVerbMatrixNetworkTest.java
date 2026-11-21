// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.copyTree;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.jk;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.nothingForecast;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.upToDate;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link VerbMatrixNoopE2eTest} on the shape of jk-examples' hotel-reservations: a Spring Boot
 * member with {@code [build-info]}, unit and integration suites, on a library with both suites,
 * in a git checkout. After the gate every verb, plain and {@code -g}, in an order that takes every
 * verb after every other, is up to date, also when {@code build-info} has its files to write again.
 *
 * <p>{@code build-info} writes {@code META-INF/build-info.properties} into the Boot member's classes
 * tree after the compile, and compile-test keys that tree with its {@code META-INF} resources. With
 * no edge between the two steps, whichever the scheduler ran first decided the test compile's key,
 * and the suite, whose stamp names that key, ran again under the verb that drew the other order:
 * {@code jk build -g} after an up-to-date {@code jk test -g} re-ran the Boot member's suite.
 * The walk removes the files {@code build-info} writes before every fourth verb, as a
 * restore that prunes them does, so the step writes while compile-test is ready to run. Which step
 * the scheduler takes first is not the test's to choose: on this small fixture it took {@code
 * build-info} first on the base too, so the edge itself is pinned by {@code
 * TestCompilesWaitForLateClassesWritersTest}, and this walk holds every verb to the outcome.
 *
 * <p>Boot comes from Maven Central, hence the network tier.
 */
@Tag("network")
class BuildInfoVerbMatrixNetworkTest {

    /** Every verb after every other and after itself: an Eulerian walk of the six. */
    static final List<String> WALK = List.of(
            "build",
            "install -g",
            "install -g",
            "install",
            "install -g",
            "test -g",
            "install -g",
            "test",
            "install -g",
            "build -g",
            "install -g",
            "build",
            "install",
            "install",
            "test -g",
            "install",
            "test",
            "install",
            "build -g",
            "install",
            "build",
            "test -g",
            "test -g",
            "test",
            "test -g",
            "build -g",
            "test -g",
            "build",
            "test",
            "test",
            "build -g",
            "test",
            "build",
            "build -g",
            "build -g",
            "build",
            "build");

    @Test
    void the_walk_takes_every_verb_after_every_other() {
        Set<String> pairs = new HashSet<>();
        for (int i = 1; i < WALK.size(); i++) pairs.add(WALK.get(i - 1) + " > " + WALK.get(i));
        assertThat(pairs).hasSize(6 * 6);
    }

    @Test
    void after_the_gate_every_verb_on_a_boot_member_with_build_info_is_up_to_date(@TempDir Path tmp) throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");
        assumeTrue(git(tmp, "--version") == 0, "no git on the PATH to make the checkout build-info reads");

        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Files.deleteIfExists(engineHome.resolve(EngineInstall.SHELF_NAME));
        assumeTrue(Files.isRegularFile(engineHome.resolve(EngineInstall.POINTER_NAME)), "no engine pointer");

        Path ws = workspace(tmp.resolve("ws"));
        try {
            var lock = jk(home, ws, "lock");
            assertThat(lock.exit()).as(lock.output()).isZero();
            assertThat(git(ws, "init", "-q")).isZero();
            assertThat(git(ws, "add", "-A")).isZero();
            assertThat(git(ws, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "one"))
                    .isZero();

            var gate = jk(home, ws, "install", "-g");
            assertThat(gate.exit()).as(gate.output()).isZero();
            Path classes = ws.resolve("app/target/classes");
            assertThat(classes.resolve("META-INF/build-info.properties")).isRegularFile();
            nothingForecast(home, ws, "explain", "-g");

            for (int i = 0; i < WALK.size(); i++) {
                if (i % 4 == 3) {
                    // What a restore that prunes the files it does not own leaves: build-info
                    // writes them again in this verb, beside a compile-test ready to key the tree.
                    Files.deleteIfExists(classes.resolve("git.properties"));
                    Files.deleteIfExists(classes.resolve("META-INF/build-info.properties"));
                }
                upToDate(home, ws, WALK.get(i).split(" "));
            }
            nothingForecast(home, ws, "explain");
            nothingForecast(home, ws, "explain", "-g");
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    /**
     * A library with {@code src/test} and {@code src/integration}, and a Spring Boot application on
     * it with {@code [build-info]} and both suites; JUnit and Boot from Central.
     */
    static Path workspace(Path root) throws IOException {
        Path ws = VerbMatrixNoopE2eTest.workspace(root);
        int java = Runtime.version().feature();
        Path app = ws.resolve("app");
        Files.writeString(app.resolve("jk.toml"), """
                name = "app"
                group.workspace = true
                version.workspace = true
                java = %d

                [application]
                main = "com.example.Main"

                [spring-boot]
                version = "4.1.2"

                [build-info]

                [dependencies]
                lib = { workspace = true }
                spring-boot = "org.springframework.boot:spring-boot"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """.formatted(java));
        write(app.resolve("src/test/java/com/example/MainTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class MainTest {
                    @Test
                    void greets() {
                        assertEquals("hello", Greeting.text());
                    }
                }
                """);
        write(app.resolve("src/integration/java/com/example/MainIT.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class MainIT {
                    @Test
                    void carries_its_build_info() {
                        assertNotNull(getClass().getResource("/META-INF/build-info.properties"));
                    }
                }
                """);
        write(ws.resolve(".gitignore"), "target/\n");
        return ws;
    }

    private static int git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = GitCommand.of();
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        try {
            return Processes.capture(pb, Duration.ofMinutes(1)).exit();
        } catch (IOException noGit) {
            return -1;
        }
    }
}
