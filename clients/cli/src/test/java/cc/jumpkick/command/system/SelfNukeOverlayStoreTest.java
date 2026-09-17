// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store nuke driven from an overlay home wipes that home's store and never the store the
 * environment names.
 *
 * <p>The shell that runs a gate may export {@code JK_STORE_DIR} for its own home. A client whose
 * home is the {@code jk.env.JK_HOME} overlay — every isolated CLI test — resolves its store under
 * that home, and so does the engine it spawns for the wipe; the environment's store is another
 * home's and out of reach. Forked, because the environment is fixed at JVM start and what the
 * environment says is the point.
 */
@Tag("integration")
class SelfNukeOverlayStoreTest {

    @Test
    void the_environments_store_survives_a_nuke_of_the_overlay_home(@TempDir Path overlayHome, @TempDir Path sentinel)
            throws Exception {
        IsolatedEngineHome.copyEngine(IsolatedEngineHome.Suite.current(), overlayHome);
        // Keep the overlay home's engine off the network: no official catalog to clone into the
        // store the nuke is about to delete.
        Files.writeString(
                overlayHome.resolve("config.toml"),
                "[templates]\nofficial = \""
                        + overlayHome.resolve("no-catalog-here").toUri() + "\"\n");
        Path kept = plant(sentinel, "repos/central/com/example/kept/1.0/kept-1.0.jar");
        Path wiped = plant(overlayHome.resolve("store"), "repos/central/com/example/demo/1.0/demo-1.0.jar");

        Run run = forkedJk(overlayHome, sentinel, "self", "nuke", "--store", "-y");

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(kept)
                .as("the store JK_STORE_DIR names belongs to the shell's home")
                .exists();
        assertThat(wiped)
                .as("the overlay home's store is what the nuke was asked for")
                .doesNotExist();
        assertThat(overlayHome.resolve("lib/jk-engine")).isDirectory();
    }

    private static Path plant(Path store, String relative) throws IOException {
        Path file = store.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "jar");
        return file;
    }

    private record Run(int exit, String output) {}

    /**
     * Fork the CLI on this JVM's classpath with {@code overlayHome} as its {@code jk.env.JK_HOME}
     * and {@code shellStore} as the environment's {@code JK_STORE_DIR} — this JVM's own layout
     * overlays stay behind, so the forked client sees only the two.
     */
    private static Run forkedJk(Path overlayHome, Path shellStore, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String arg : EngineSpawn.forwardedJvmArgs()) {
            if (!arg.startsWith("-Djk.env.")) cmd.add(arg);
        }
        cmd.add("-Djk.env.JK_HOME=" + overlayHome);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Jk.class.getName());
        cmd.add("--no-ansi");
        cmd.add("--no-progress");
        cmd.addAll(List.of(args));
        ProcessBuilder pb =
                new ProcessBuilder(cmd).directory(overlayHome.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_STORE_DIR", shellStore.toString());
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
}
