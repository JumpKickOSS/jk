// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AotCacheFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker side of the read-time TTL on sticky {@code .noaot} markers: a failed train backs its
 * key off, and a marker older than {@link AotCacheFiles#MARKER_TTL_MILLIS} expires so the key gets
 * a fresh attempt. The sweep-side expiry runs only from a <em>successful</em> train of a sibling
 * key, so a tool whose sole key failed depends entirely on this one.
 *
 * <p>Driven through {@link PluginAot#ensureTrained} rather than the predicate, because the fact
 * under test is that the worker's own decision reads the shared window — a private copy of the
 * rule would satisfy a test of the predicate alone. {@code EngineAotCacheTest} pins the same
 * window from the engine's spawn decision.
 *
 * <p>The trainer throws instead of returning a command line: "training was attempted" is the
 * observable, and no fork is needed to see it.
 */
class PluginAotNoAotMarkerTest {

    private static final String TOOL = "kotlinc";
    private static final String WORKER_CP = "/worker/kotlinc.jar";

    @TempDir
    Path tmp;

    private Path jdkHome;
    private String prevState;
    private String prevTrain;
    private String prevWorkerAot;

    @BeforeEach
    void isolateState() throws IOException {
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        prevTrain = System.getProperty("jk.aot.train");
        prevWorkerAot = System.getProperty("jk.worker.aot");
        System.setProperty(
                "jk.env.JK_STATE_DIR", tmp.resolve("state").toAbsolutePath().toString());
        // Ambient JK_AOT_TRAIN=off (jk test workers / CI) would make every arm skip training and
        // the test agree with itself for the wrong reason. Properties win over the environment.
        System.setProperty("jk.aot.train", "on");
        System.setProperty("jk.worker.aot", "on");
        jdkHome = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(jdkHome.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.3\"\n");
    }

    @AfterEach
    void restore() {
        restore("jk.env.JK_STATE_DIR", prevState);
        restore("jk.aot.train", prevTrain);
        restore("jk.worker.aot", prevWorkerAot);
    }

    private static void restore(String key, String prev) {
        if (prev == null) System.clearProperty(key);
        else System.setProperty(key, prev);
    }

    /** Plant a refusal marker for this host's kotlinc key, aged {@code ageMillis}. */
    private Path plantMarker(long ageMillis) throws IOException {
        Path cache = requireNonNull(PluginAot.cachePath(TOOL, jdkHome, WORKER_CP));
        Files.createDirectories(requireNonNull(cache.getParent()));
        Path marker = Files.createFile(AotCacheFiles.marker(cache));
        Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
        return marker;
    }

    /** Did the worker path reach its trainer? */
    private boolean trainAttempted() {
        AtomicBoolean reached = new AtomicBoolean();
        PluginAot.ensureTrained(
                TOOL,
                jdkHome,
                WORKER_CP,
                (aotOutput, scratch) -> {
                    reached.set(true);
                    throw new IOException("no fork needed: reaching the trainer is the observable");
                },
                5_000);
        return reached.get();
    }

    @Test
    void a_marker_inside_the_window_still_blocks_training() throws IOException {
        Path marker = plantMarker(AotCacheFiles.MARKER_TTL_MILLIS - 60_000);

        assertThat(trainAttempted()).isFalse();
        assertThat(marker).exists(); // still backing off
    }

    @Test
    void a_marker_older_than_the_window_is_expired_and_the_key_retrains() throws IOException {
        Path marker = plantMarker(AotCacheFiles.MARKER_TTL_MILLIS + 60_000);

        assertThat(trainAttempted()).isTrue();
        assertThat(marker).doesNotExist(); // expired marker is deleted, not merely ignored
    }

    @Test
    void no_marker_means_no_refusal_to_honour() {
        assertThat(trainAttempted()).isTrue();
    }
}
