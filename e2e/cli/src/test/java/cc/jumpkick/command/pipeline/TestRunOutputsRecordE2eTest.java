// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.copyTree;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.jk;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.nothingForecast;
import static cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.command.pipeline.VerbMatrixNoopE2eTest.Run;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a module's outputs record names, and when it is written, decide whether {@code jk explain}
 * after a run calls the module's outputs "built from other inputs"
 * (hotel-reservations, every module, with every step cached). Three causes, one test each, on the
 * smallest workspace that shows them: {@code lib}, whose jar {@code app} reads, and {@code app}.
 * ({@code jk explain -m} does not check a module's outputs record, so these forecast the whole
 * workspace.)
 * Neither has a test or a dependency from the network; the one dependency is served by a loopback
 * repository. On a forked client against a private {@code JK_HOME}, as {@link VerbMatrixNoopE2eTest}.
 */
@Tag("integration")
class TestRunOutputsRecordE2eTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    private static final String GROUP = "com.example.testrunrecord";

    /**
     * A green {@code jk test} records the outputs it leaves: lib's edit, compiled and packaged by a
     * test run (app's compile reads lib's jar), left the record of the build before it, so the
     * forecast called lib's outputs another build's until the next {@code jk build}.
     */
    @Test
    void a_green_test_run_records_the_outputs_it_packaged(@TempDir Path tmp) throws Exception {
        Path home = home(tmp);
        Path ws = workspace(tmp.resolve("ws"), "");
        try {
            ok(jk(home, ws, "lock"));
            ok(jk(home, ws, "build"));
            Path greeting = ws.resolve("lib/src/main/java/com/example/Greeting.java");
            Files.writeString(greeting, Files.readString(greeting).replace("\"hello\"", "\"hel\" + \"lo\""));
            ok(jk(home, ws, "test", "-g"));
            nothingForecast(home, ws, "explain", "-g");
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    /**
     * A red {@code jk test} records the outputs of the modules it finished green: lib's edit breaks
     * app's compile, and the red run left no record at all, so the forecast called lib's outputs
     * another build's beside app's broken compile.
     */
    @Test
    void a_red_test_run_records_the_outputs_of_its_green_modules(@TempDir Path tmp) throws Exception {
        Path home = home(tmp);
        Path ws = workspace(tmp.resolve("ws"), "");
        try {
            ok(jk(home, ws, "lock"));
            ok(jk(home, ws, "build"));
            Path greeting = ws.resolve("lib/src/main/java/com/example/Greeting.java");
            Files.writeString(greeting, Files.readString(greeting).replace("text()", "words()"));
            Run red = jk(home, ws, "test", "-g");
            assertThat(red.exit()).as(red.output()).isNotZero();
            Run forecast = jk(home, ws, "explain", "-g", "-O", "jsonl");
            assertThat(forecast.exit()).as(forecast.output()).isZero();
            assertThat(forecast.output())
                    .as("only app, whose compile is broken, rebuilds")
                    .contains("\"dirty\":1,")
                    .doesNotContain("testrunrecord:lib\",\"step\":\"package-jar\",\"status\":\"run\"");
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    /**
     * A relock that moves only app's rows leaves lib's outputs lib's: the record named the whole
     * workspace lock, so any relock made every module's outputs another build's.
     */
    @Test
    void a_relock_of_another_member_leaves_the_record_standing(@TempDir Path tmp) throws Exception {
        maven.registerPom(GROUP, "alpha", "1.0", MockMavenServer.pom(GROUP, "alpha", "1.0"));
        maven.registerJar(GROUP, "alpha", "1.0", jar());
        Path home = home(tmp);
        Path ws = workspace(tmp.resolve("ws"), "[repositories]\nstub = \"" + maven.baseUrl() + "\"\n");
        try {
            ok(jk(home, ws, "lock"));
            ok(jk(home, ws, "build"));
            nothingForecast(home, ws, "explain");
            Path app = ws.resolve("app/jk.toml");
            Files.writeString(
                    app, Files.readString(app) + "\n[runtime-dependencies]\nalpha = \"" + GROUP + ":alpha:1.0\"\n");
            ok(jk(home, ws, "lock"));
            // app's runtime moved, so app repackages; lib's outputs are still lib's.
            Run forecast = jk(home, ws, "explain", "-O", "jsonl");
            assertThat(forecast.exit()).as(forecast.output()).isZero();
            assertThat(forecast.output())
                    .as("only app's jar moves")
                    .contains("\"dirty\":1,")
                    .doesNotContain("testrunrecord:lib\",\"step\":\"package-jar\",\"status\":\"run\"");
        } finally {
            jk(home, ws, "engine", "stop", "--now");
        }
    }

    private static void ok(Run run) {
        assertThat(run.exit()).as(run.output()).isZero();
    }

    /** A private home seeded with the sandbox's engine. */
    private static Path home(Path tmp) throws IOException {
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

    /** {@code lib}, and {@code app} on it; no tests, and only the repositories {@code repositories} names. */
    private static Path workspace(Path root, String repositories) throws IOException {
        int java = Runtime.version().feature();
        write(root.resolve("jk.toml"), """
                group = "%s"
                name = "record"
                version = "0.1.0"
                java = %d

                [workspace]
                modules = ["lib", "app"]

                [m2]
                integration = false

                %s""".formatted(GROUP, java, repositories));
        write(root.resolve("lib/jk.toml"), """
                name = "lib"
                group.workspace = true
                version.workspace = true
                java = %d
                """.formatted(java));
        write(root.resolve("lib/src/main/java/com/example/Greeting.java"), """
                package com.example;
                public final class Greeting {
                    public static String text() { return "hello"; }
                }
                """);
        write(root.resolve("app/jk.toml"), """
                name = "app"
                group.workspace = true
                version.workspace = true
                java = %d

                [dependencies]
                lib = { workspace = true }
                """.formatted(java));
        write(root.resolve("app/src/main/java/com/example/Main.java"), """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.out.println(Greeting.text()); }
                }
                """);
        return root;
    }

    /** A jar holding one resource: what the stub repository serves for alpha. */
    private static byte[] jar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("alpha.txt"));
            out.write("alpha\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
