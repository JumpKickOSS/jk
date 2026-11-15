// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verbs share one tree: after the push gate ({@code jk install -g}) has built and tested a tree, any
 * order of {@code jk build}, {@code jk test}, {@code jk install} and the gate again, an engine
 * restart among them, runs nothing and forecasts nothing. The gate compiled its suites into the
 * test-classes tree and ran the default suite with them; a plain build compiled the default suite
 * alone, so it took the gate's tree for another build's outputs ("its outputs on disk were built
 * from other inputs"), recompiled the tests and ran the default suite a second time, and the gate
 * after it recompiled them back (JK-YEHQT). The fixture has what makes the shapes differ: a member
 * with an {@code integration} suite directory beside {@code src/test}, each with resources.
 *
 * <p>Proven on a forked client against a private {@code JK_HOME} seeded with the sandbox's engine,
 * as {@link InstallFixedPointE2eTest} is, since the restart is a real engine process's.
 */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class VerbMatrixNoopE2eTest {

    @Test
    void after_the_gate_every_verb_in_any_order_is_up_to_date(@TempDir Path tmp) throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");

        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Files.deleteIfExists(engineHome.resolve(EngineInstall.SHELF_NAME));
        assumeTrue(Files.isRegularFile(engineHome.resolve(EngineInstall.POINTER_NAME)), "no engine pointer");

        Path ws = workspace(tmp.resolve("ws"));
        try {
            Run lock = jk(home, ws, "lock");
            assertThat(lock.exit()).as(lock.output()).isZero();

            Run gate = jk(home, ws, "install", "-g");
            assertThat(gate.exit()).as(gate.output()).isZero();
            assertThat(gate.output()).doesNotContain("up to date");

            upToDate(home, ws, "build");
            upToDate(home, ws, "test");
            nothingForecast(home, ws, "explain");
            upToDate(home, ws, "install");

            Run stop = jk(home, ws, "engine", "stop", "--now");
            assertThat(stop.exit()).as(stop.output()).isZero();

            upToDate(home, ws, "install", "-g");
            upToDate(home, ws, "build");
            nothingForecast(home, ws, "explain");
            nothingForecast(home, ws, "explain", "-g");
            upToDate(home, ws, "test");
            upToDate(home, ws, "install", "-g");
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    private static void upToDate(Path home, Path ws, String... verb) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of(verb));
        args.add("--agent"); // the terse verdict, which names a no-op "up to date" in every verb
        Run run = jk(home, ws, args.toArray(new String[0]));
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output())
                .as("jk %s after the gate", String.join(" ", verb))
                .contains("up to date");
    }

    private static void nothingForecast(Path home, Path ws, String... verb) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of(verb));
        args.addAll(List.of("-O", "jsonl"));
        Run run = jk(home, ws, args.toArray(new String[0]));
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output())
                .as("jk %s after the gate", String.join(" ", verb))
                .contains("\"type\":\"explain\"")
                .contains("\"dirty\":0,")
                .doesNotContain("\"status\":\"run\"");
    }

    /**
     * A library whose tests live in {@code src/test} and {@code src/integration}, each suite with a
     * resource of its own, and an application on it; JUnit from Central.
     */
    static Path workspace(Path root) throws IOException {
        int java = Runtime.version().feature();
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "verbs"
                version = "0.1.0"
                java = %d

                [workspace]
                modules = ["lib", "app"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [m2]
                integration = false

                [install]
                destination = "jk"
                """.formatted(java));
        Path lib = root.resolve("lib");
        Files.writeString(Files.createDirectories(lib).resolve("jk.toml"), """
                name = "lib"
                group.workspace = true
                version.workspace = true
                java = %d

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """.formatted(java));
        write(lib.resolve("src/main/java/com/example/Greeting.java"), """
                package com.example;
                public final class Greeting {
                    public static String text() { return "hello"; }
                }
                """);
        write(lib.resolve("src/test/java/com/example/GreetingTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class GreetingTest {
                    @Test
                    void says_hello() {
                        assertEquals("hello", Greeting.text());
                        assertNotNull(getClass().getResource("/unit.properties"));
                    }
                }
                """);
        write(lib.resolve("src/test/resources/unit.properties"), "suite=test\n");
        write(lib.resolve("src/integration/java/com/example/GreetingIT.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class GreetingIT {
                    @Test
                    void says_hello_end_to_end() {
                        assertEquals("hello", Greeting.text());
                        assertNotNull(getClass().getResource("/integration.properties"));
                    }
                }
                """);
        write(lib.resolve("src/integration/resources/integration.properties"), "suite=integration\n");
        Path app = root.resolve("app");
        Files.writeString(Files.createDirectories(app).resolve("jk.toml"), """
                name = "app"
                group.workspace = true
                version.workspace = true
                java = %d

                [application]
                main = "com.example.Main"

                [dependencies]
                lib = { workspace = true }
                """.formatted(java));
        write(app.resolve("src/main/java/com/example/Main.java"), """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.out.println(Greeting.text()); }
                }
                """);
        return root;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private record Run(int exit, String output) {}

    /** Fork the CLI on this JVM's classpath against {@code home} in {@code ws}; bounded, output captured. */
    private static Run jk(Path home, Path ws, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineSpawn.forwardedJvmArgs()); // the worker-jar overrides, for the engine this client spawns
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Jk.class.getName());
        cmd.add("--no-ansi");
        cmd.add("--no-progress");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ws.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_HOME", home.toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("JK_ENGINE_EXE");
        Processes.Captured run = Processes.capture(pb, Duration.ofMinutes(4));
        if (run.timedOut()) {
            throw new IOException(
                    "jk " + String.join(" ", args) + " did not finish within 4 minutes:\n" + run.output());
        }
        return new Run(run.exit(), "jk " + String.join(" ", args) + "\n" + run.output());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
