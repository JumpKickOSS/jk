// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.Await;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A trainer runs with <em>store jars</em> on its classpath, so it is the reader standing between
 * {@code jk storage nuke} and the store. Stopping the engines never reached it: the fork outlives
 * the request that started it, and on Windows an open jar cannot be deleted at all.
 */
@Tag("integration")
class PluginAotQuiesceTest {

    @TempDir
    Path tmp;

    /** A real jar so a JVM will map it rather than reject it. */
    private Path storeJar() throws IOException {
        Path jar = Files.createDirectories(tmp.resolve("store/repos/central")).resolve("held.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("held.txt"));
            out.write("held".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /**
     * A trainer that holds the store jar open, the way a real one holds its worker jars. The jar
     * goes <em>first</em> on the classpath so the JVM searches (and therefore opens) it before
     * finding {@link SleepForever} in the test classes — a jar the JVM never searches is a jar it
     * never opens, and the test would pass without reproducing anything.
     */
    private static PluginAot.TrainerCommand holdingTrainer(Path jar) {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        String cp = jar.toAbsolutePath() + File.pathSeparator + System.getProperty("java.class.path");
        return (aotOutput, scratch) -> List.of(java, "-cp", cp, SleepForever.class.getName());
    }

    @Test
    void quiescing_trainers_lets_the_store_wipe_delete_jars_they_held() throws Exception {
        Path jar = storeJar();
        Path store = tmp.resolve("store");
        Path cache = Files.createDirectories(tmp.resolve("aot")).resolve("javac-held00000000000.aot");

        PluginAot.trainAsync("test", cache, holdingTrainer(jar));
        Await.until(Duration.ofSeconds(30), PluginAot::trainingInFlight);

        List<Long> killed = PluginAot.quiesceTrainers(10_000);

        assertThat(killed)
                .as("the fork holding the store jar is the one to stop")
                .isNotEmpty();
        // The delete is the assertion: on Windows this throws while the fork lives.
        PathUtil.deleteRecursivelyOrThrow(store);
        assertThat(store).doesNotExist();
    }

    @Test
    void quiescing_with_no_trainer_running_reports_nothing_killed() {
        assertThat(PluginAot.quiesceTrainers(1_000)).isEmpty();
    }
}
