// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.util.FileLocks;
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
 * A build-like job another process holds the checkout for is refused by a real engine with that
 * holder's build number. The holder here is this test process on {@code target/.jk/build.lock}
 * — the same lock a second engine would hold — so the refusal is proven end to end through a
 * forked client and the engine it spawns, without racing two builds. Slow tier: it starts an
 * engine in a private {@code JK_HOME}.
 */
@Tag("slow")
class BuildSlotRefusalE2eTest {

    @Test
    void a_checkout_another_process_holds_is_refused_with_its_build_number(@TempDir Path tmp) throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");
        Path home = tmp.resolve("home");
        copyTree(sandboxEngine.get().root(), home.resolve("lib").resolve(EngineInstall.BIN_NAME));
        Path project = project(tmp.resolve("app"));
        Path lock = project.resolve("target").resolve(".jk").resolve("build.lock");
        try {
            FileLocks.Hold held = (FileLocks.Hold) FileLocks.tryHold(lock);
            try {
                held.write("pid=" + ProcessHandle.current().pid() + "\nbuild=7\nkind=build\nstarted=1\n");
                Run refused = jk(home, project, "build", "--skip-tests");
                assertThat(refused.exit()).as(refused.output()).isNotZero();
                assertThat(refused.output()).contains("Build #7 is already running");
                assertThat(project.resolve("target/classes"))
                        .as("nothing built under a held slot")
                        .doesNotExist();
            } finally {
                held.close();
            }
            Run built = jk(home, project, "build", "--skip-tests");
            assertThat(built.exit()).as(built.output()).isZero();
            FileLocks.Probe released = FileLocks.tryHold(lock);
            assertThat(released).as("the engine released the slot with the job").isInstanceOf(FileLocks.Hold.class);
            ((FileLocks.Hold) released).close();
        } finally {
            jk(home, project, "engine", "stop", "--now");
        }
    }

    /** One class, nothing external to resolve. */
    private static Path project(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(
                root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "0.1.0"
                java    = %d
                """.formatted(Runtime.version().feature()));
        Files.writeString(root.resolve("src/main/java/com/example/App.java"), """
                package com.example;

                public final class App {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);
        return root;
    }

    private record Run(int exit, String output) {}

    /** Fork the CLI on this JVM's classpath against {@code home}; bounded, output captured. */
    private static Run jk(Path home, Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineSpawn.forwardedJvmArgs());
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
