// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A root {@code jk.toml} that groups self-contained workspaces, end to end on a forked
 * client against a private {@code JK_HOME} seeded with the sandbox's engine, as {@link
 * cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest} is.
 *
 * <p>The fixture: {@code libs}, a workspace with one module and a test; {@code greeting}, a single
 * project; {@code apps/hello}, a single project with a {@code path} dependency on {@code greeting};
 * {@code apps/heavy}, excluded by default with a reason, which the root profile {@code all} adds back.
 */
// Network: JUnit from Central; forked test JVMs.
@Tag("integration")
class GroupRootE2eTest {

    @Test
    void the_root_runs_its_members_in_order_then_nothing_and_a_member_builds_the_same_without_it(@TempDir Path tmp)
            throws Exception {
        Path home = privateHome(tmp);
        Path root = group(tmp.resolve("group"));
        try {
            Run build = jk(home, root, "build");
            assertThat(build.exit()).as(build.output()).isZero();
            assertThat(build.output())
                    .contains("apps/heavy: needs a toolchain this machine lacks (jk build -p all includes it)")
                    .contains("build > group - 3 workspaces")
                    .doesNotContain("build acme-heavy");

            // The second run of every verb, in several orders, an engine restart among them, does nothing.
            upToDate(home, root, "build");
            Run gate = jk(home, root, "test", "-g");
            assertThat(gate.exit()).as(gate.output()).isZero();
            upToDate(home, root, "test", "-g");
            Run install = jk(home, root, "install", "-g");
            assertThat(install.exit()).as(install.output()).isZero();
            upToDate(home, root, "build");
            nothingForecast(home, root);
            upToDate(home, root, "install", "-g");
            Run stop = jk(home, root, "engine", "stop", "--now");
            assertThat(stop.exit()).as(stop.output()).isZero();
            nothingForecast(home, root);
            upToDate(home, root, "test", "-g");
            upToDate(home, root, "build");

            // A member by name runs with what it depends on, and nothing else; an unknown name lists them.
            Run one = jk(home, root, "build", "-m", "acme-hello", "-O", "jsonl");
            assertThat(one.exit()).as(one.output()).isZero();
            String greetingDir = dirField(root.toRealPath().resolve("greeting"));
            String helloDir = dirField(root.toRealPath().resolve("apps/hello"));
            assertThat(one.output().indexOf(greetingDir))
                    .as("the member a path dependency names runs first")
                    .isNotNegative()
                    .isLessThan(one.output().indexOf(helloDir));
            assertThat(one.output())
                    .contains("\"type\":\"group-finish\"")
                    .contains("\"ok\":[\"greeting\",\"apps/hello\"]")
                    .doesNotContain(inJson(root.toRealPath().resolve("libs")));
            Run unknown = jk(home, root, "build", "-m", "nope");
            assertThat(unknown.exit()).as(unknown.output()).isNotZero();
            assertThat(unknown.output())
                    .contains("no member named `nope`")
                    .contains("libs, apps/heavy, apps/hello, greeting");

            // The root profile adds the excluded member back.
            Run all = jk(home, root, "build", "-p", "all");
            assertThat(all.exit()).as(all.output()).isZero();
            assertThat(all.output()).contains("group - 4 workspaces").doesNotContain("skip > ");

            // A release belongs to one workspace.
            for (String verb : List.of("release", "publish")) {
                Run refused = jk(home, root, verb);
                assertThat(refused.exit()).as(refused.output()).isEqualTo(2);
                assertThat(refused.output())
                        .contains("has no version of its own")
                        .contains("jk -C <member> " + verb);
            }

            // Self-containment: without the root file, each member cleaned and built again keys every
            // step as it did from the root, so the action cache gains no key.
            Path hello = root.resolve("apps/hello");
            Path libs = root.resolve("libs");
            Files.move(root.resolve("jk.toml"), root.resolve("jk.toml.off"));
            Set<String> before = actionKeys(home);
            assertThat(before).isNotEmpty();
            for (Path member : List.of(root.resolve("greeting"), hello, libs)) {
                Run clean = jk(home, member, "clean");
                assertThat(clean.exit()).as(clean.output()).isZero();
                Run alone = jk(home, member, "build");
                assertThat(alone.exit()).as(alone.output()).isZero();
            }
            assertThat(actionKeys(home))
                    .as("action keys after building each member alone")
                    .isEqualTo(before);
            Files.move(root.resolve("jk.toml.off"), root.resolve("jk.toml"));
            upToDate(home, root, "build");
        } finally {
            jk(home, root, "engine", "stop", "--now");
        }
    }

    private static void upToDate(Path home, Path root, String... verb) throws IOException, InterruptedException {
        Run run = jk(home, root, verb);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output())
                .as("jk %s at the root, second time", String.join(" ", verb))
                .contains("group - 3 workspaces - up to date");
    }

    private static void nothingForecast(Path home, Path root) throws IOException, InterruptedException {
        Run run = jk(home, root, "explain", "-O", "jsonl");
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Pattern.compile("\"type\":\"explain\"")
                        .matcher(run.output())
                        .results()
                        .count())
                .as("one forecast per member")
                .isEqualTo(3);
        assertThat(run.output()).doesNotContain("\"status\":\"run\"").contains("\"type\":\"group-finish\"");
        assertThat(Pattern.compile("\"dirty\":0,")
                        .matcher(run.output())
                        .results()
                        .count())
                .isEqualTo(3);
    }

    /** The action keys the private home's cache holds: one file per key under {@code actions/keys}. */
    private static Set<String> actionKeys(Path home) throws IOException {
        Path keys = home.resolve("cache/actions/keys");
        try (Stream<Path> files = Files.list(keys)) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The group: a workspace, a project with a path dependency into it, and an excluded project. */
    static Path group(Path root) throws IOException {
        int java = Runtime.version().feature();
        String common = """

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [m2]
                integration = false

                [install]
                destination = "jk"
                """;
        write(root.resolve("jk.toml"), """
                [workspaces]
                members = ["libs", "apps/*", "greeting"]
                exclude = [{ path = "apps/heavy", reason = "needs a toolchain this machine lacks" }]

                [profiles.all.workspaces]
                include = ["apps/heavy"]
                """);
        write(root.resolve("libs/jk.toml"), """
                group = "com.example"
                name = "acme-libs"
                version = "1.0.0"
                java = %d

                [workspace]
                modules = ["core"]
                """.formatted(java) + common);
        write(root.resolve("libs/core/jk.toml"), """
                name = "core"
                group.workspace = true
                version.workspace = true
                java = %d

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """.formatted(java));
        write(root.resolve("libs/core/src/main/java/com/example/Greeting.java"), """
                package com.example;
                public final class Greeting {
                    public static String text() { return "hello"; }
                }
                """);
        write(root.resolve("greeting/jk.toml"), """
                group = "com.example"
                name = "greeting"
                version = "1.0.0"
                java = %d
                """.formatted(java) + common);
        write(root.resolve("greeting/src/main/java/com/example/Greeting.java"), """
                package com.example;
                public final class Greeting {
                    public static String text() { return "hello"; }
                }
                """);
        write(root.resolve("libs/core/src/test/java/com/example/GreetingTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreetingTest {
                    @Test
                    void says_hello() {
                        assertEquals("hello", Greeting.text());
                    }
                }
                """);
        write(root.resolve("apps/hello/jk.toml"), """
                group = "com.example"
                name = "acme-hello"
                version = "2.0.0"
                java = %d

                [application]
                main = "com.example.Main"

                [dependencies]
                greeting = { path = "../../greeting" }
                """.formatted(java) + common);
        write(root.resolve("apps/hello/src/main/java/com/example/Main.java"), """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.out.println(Greeting.text()); }
                }
                """);
        write(root.resolve("apps/heavy/jk.toml"), """
                group = "com.example"
                name = "acme-heavy"
                version = "0.1.0"
                java = %d
                """.formatted(java) + common);
        write(root.resolve("apps/heavy/src/main/java/com/example/Heavy.java"), """
                package com.example;
                public final class Heavy {}
                """);
        return root;
    }

    private static Path privateHome(Path tmp) throws IOException {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");
        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Files.deleteIfExists(engineHome.resolve(EngineInstall.SHELF_NAME));
        assumeTrue(Files.isRegularFile(engineHome.resolve(EngineInstall.POINTER_NAME)), "no engine pointer");
        return home;
    }

    /** The JSONL {@code "dir"} field naming {@code dir}. */
    private static String dirField(Path dir) {
        return "\"dir\":\"" + inJson(dir);
    }

    /** {@code path} as a JSON string spells it: a Windows path's backslashes are escaped. */
    private static String inJson(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private record Run(int exit, String output) {}

    /** Fork the CLI on this JVM's classpath against {@code home} in {@code dir}; bounded, output captured. */
    private static Run jk(Path home, Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineSpawn.forwardedJvmArgs());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Jk.class.getName());
        cmd.add("--no-ansi");
        cmd.add("--no-progress");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_HOME", home.toString());
        // The action cache is the home's own, so its keys are this test's alone.
        pb.environment().put("JK_CACHE_DIR", home.resolve("cache").toString());
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
