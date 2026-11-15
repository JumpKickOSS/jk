// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.BuildIdentity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build after installing an engine that differs from the one before only in its packaging
 * stamps is a no-op. jk's push gate installs the engine it builds, and the engine jar carries the
 * commit and commit time it was packaged from, so a gate after a test-only edit to the engine
 * module installs a jar of other bytes and the same code. Every action key used to fold in the
 * whole jar's sha, so the next gate on the unchanged tree rebuilt every module and ran every suite
 * again (JK-E7GKJ); keyed by the code, it replays.
 *
 * <p>Proven on a forked client against a private {@code JK_HOME} seeded with the sandbox's engine,
 * as {@link InstallFixedPointE2eTest} is: the property lives in the engine process's own identity,
 * which an in-process engine running from a classes directory does not have.
 */
@Tag("integration")
class RestampedEngineNoopE2eTest {

    @Test
    void an_engine_repackaged_with_only_new_stamps_replays_the_build(@TempDir Path tmp) throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");

        Path home = tmp.resolve("home");
        Path engineHome = home.resolve("lib").resolve(EngineInstall.BIN_NAME);
        copyTree(sandboxEngine.get().root(), engineHome);
        Files.deleteIfExists(engineHome.resolve(EngineInstall.SHELF_NAME));
        Path pointer = engineHome.resolve(EngineInstall.POINTER_NAME);
        assumeTrue(Files.isRegularFile(pointer), "the sandbox engine home carries no pointer");
        Path jar =
                engineHome.resolve(sandboxEngine.get().engineJar().getFileName().toString());
        assumeTrue(Files.isRegularFile(jar), "the sandbox engine home holds no engine jar");

        Path ws = InstallFixedPointE2eTest.workspace(tmp.resolve("ws"));
        try {
            Run first = jk(home, ws, "build", "-C", ws.toString(), "--skip-tests");
            assertThat(first.exit()).as(first.output()).isZero();
            assertThat(first.output()).doesNotContain("up to date");
            Run stop = jk(home, ws, "engine", "stop", "--now");
            assertThat(stop.exit()).as(stop.output()).isZero();

            // What the next commit's install leaves: the same engine code under new stamps.
            String before = Hashing.sha256Hex(jar);
            restamp(jar, "a2136bc4924d9b3e8afce29e25e8ce72a48d060e", "2031-01-01T00:00:00Z");
            String after = Hashing.sha256Hex(jar);
            assertThat(after).isNotEqualTo(before);
            String text = Files.readString(pointer);
            assertThat(text).contains(before);
            Files.writeString(pointer, text.replace(before, after));

            Run second = jk(home, ws, "build", "-C", ws.toString(), "--skip-tests");
            assertThat(second.exit()).as(second.output()).isZero();
            assertThat(second.output()).as(second.output()).contains("up to date");
            Run status = jk(home, ws, "engine", "status");
            assertThat(status.exit()).as(status.output()).isZero();
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    /** Rewrite {@code jar} in place with its manifest's commit and build-time stamps set to these. */
    private static void restamp(Path jar, String commit, String time) throws IOException {
        Path out = jar.resolveSibling(jar.getFileName() + ".restamped");
        try (ZipFile in = new ZipFile(jar.toFile());
                OutputStream file = Files.newOutputStream(out);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (ZipEntry e : Collections.list(in.entries())) {
                byte[] bytes;
                try (InputStream s = in.getInputStream(e)) {
                    bytes = s.readAllBytes();
                }
                if (e.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
                    Manifest m = new Manifest(new ByteArrayInputStream(bytes));
                    m.getMainAttributes().putValue(BuildIdentity.BUILD_COMMIT_ATTRIBUTE, commit);
                    m.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, time);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    m.write(buf);
                    bytes = buf.toByteArray();
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zip.putNextEntry(copy);
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        Files.move(out, jar, StandardCopyOption.REPLACE_EXISTING);
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
        Processes.Captured run = Processes.capture(pb, Duration.ofMinutes(4));
        if (run.timedOut()) {
            throw new IOException(
                    "jk " + String.join(" ", args) + " did not finish within 4 minutes:\n" + run.output());
        }
        return new Run(run.exit(), run.output());
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
