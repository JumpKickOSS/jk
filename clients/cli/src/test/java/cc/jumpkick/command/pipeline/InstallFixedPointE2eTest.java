// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two consecutive {@code jk install}s of one tree reach a fixed point: the second re-shelves
 * nothing, and the home names the same engine before and after it. The property spans the whole
 * install — packagers, artifact keys carrying the engine identity, the second pass's handoff — so
 * it is proven on a forked client against a private {@code JK_HOME}, not in-process.
 *
 * <p>The forked client is this test JVM's own classpath ({@code cc.jumpkick.cli.Jk}), the home
 * is seeded with the sandbox's engine (jar + pointer), and every wait is bounded. Slow tier: it
 * starts an engine and installs a two-module workspace twice.
 */
@Tag("slow")
class InstallFixedPointE2eTest {

    @Test
    void installing_twice_reshelves_nothing_and_leaves_the_engine_pointer_unchanged(@TempDir Path tmp)
            throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");

        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Path pointer = engineHome.resolve(EngineInstall.POINTER_NAME);
        assumeTrue(Files.isRegularFile(pointer), "the sandbox engine home carries no pointer");
        byte[] pointerBefore = Files.readAllBytes(pointer);

        Path ws = workspace(tmp.resolve("ws"));
        try {
            Run first = jk(home, ws, "install", "-C", ws.toString(), "--skip-tests");
            assertThat(first.exit()).as(first.output()).isZero();
            assertThat(first.output()).contains("Installed com.example:app:0.1.0");
            assertThat(first.output()).doesNotContain("re-shelving");

            Run second = jk(home, ws, "install", "-C", ws.toString(), "--skip-tests");
            assertThat(second.exit()).as(second.output()).isZero();
            assertThat(second.output()).contains("everything already installed");
            assertThat(second.output()).doesNotContain("re-shelving").doesNotContain("Installed com.example");

            assertThat(Files.readAllBytes(pointer)).isEqualTo(pointerBefore);
            assertThat(new EngineInstall(home.resolve("lib"))
                            .currentInstall()
                            .map(EngineInstall.Materialized::engineSha))
                    .contains(sandboxEngine.get().engineSha());
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    /** A root with a library and an application that depends on it; nothing external to resolve. */
    private static Path workspace(Path root) throws IOException {
        int java = Runtime.version().feature();
        Files.createDirectories(root.resolve("lib/src/main/java/com/example"));
        Files.createDirectories(root.resolve("app/src/main/java/com/example"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "fixed-point"
                version = "0.1.0"
                java = %d

                [workspace]
                modules = ["lib", "app"]

                [m2]
                integration = false
                install = false
                """.formatted(java));
        Files.writeString(root.resolve("lib/jk.toml"), """
                name = "lib"
                group.workspace = true
                version.workspace = true
                java = %d
                """.formatted(java));
        Files.writeString(root.resolve("lib/src/main/java/com/example/Greeting.java"), """
                package com.example;
                public final class Greeting {
                    public static String text() { return "hello"; }
                }
                """);
        Files.writeString(root.resolve("app/jk.toml"), """
                name = "app"
                group.workspace = true
                version.workspace = true
                java = %d

                [application]
                main = "com.example.Main"

                [dependencies]
                lib = { workspace = true }
                """.formatted(java));
        Files.writeString(root.resolve("app/src/main/java/com/example/Main.java"), """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.out.println(Greeting.text()); }
                }
                """);
        return root;
    }

    private record Run(int exit, String output) {}

    /** Fork the CLI on this JVM's classpath against {@code home}; bounded, output captured. */
    private static Run jk(Path home, Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineSpawn.forwardedJvmArgs()); // the worker-jar overrides, for the engine this client spawns
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Jk.class.getName());
        cmd.add("--no-ansi");
        cmd.add("--no-progress");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_HOME", home.toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("JK_ENGINE_EXE");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(4, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("jk " + String.join(" ", args) + " did not finish within 4 minutes:\n"
                    + new String(out, StandardCharsets.UTF_8));
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
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
