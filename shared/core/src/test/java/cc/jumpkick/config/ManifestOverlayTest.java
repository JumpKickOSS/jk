// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * An update's unaccepted manifests are what every pool task and started thread of its resolve
 * reads, so no part of it reads the files on disk, and no other work sees them.
 */
class ManifestOverlayTest {

    private static final Path MANIFEST = Path.of("/ws/app/jk.toml").toAbsolutePath();
    private static final String TEXT = "[project]\nname = \"app\"\n";

    @Test
    void every_pool_and_started_thread_reads_the_overlay() throws Exception {
        List<@Nullable String> seen = ManifestOverlay.reading(Map.of(MANIFEST, TEXT), ManifestOverlayTest::sample);

        assertThat(seen).hasSize(5).containsOnly(TEXT);
    }

    @Test
    void work_started_outside_an_overlay_reads_the_disk() throws Exception {
        assertThat(sample()).hasSize(5).containsOnlyNulls();
    }

    private static List<@Nullable String> sample() throws Exception {
        List<@Nullable String> seen = new ArrayList<>();
        for (ExecutorService pool : List.of(JkThreads.cpu(), JkThreads.io(), JkThreads.platform())) {
            seen.add(pool.submit(ManifestOverlayTest::read).get());
        }
        AtomicReference<@Nullable String> onVirtual = new AtomicReference<>();
        SessionContext.startVirtual("overlay-probe", () -> onVirtual.set(read()))
                .join();
        seen.add(onVirtual.get());
        AtomicReference<@Nullable String> onPlatform = new AtomicReference<>();
        SessionContext.startPlatform("overlay-probe", () -> onPlatform.set(read()))
                .join();
        seen.add(onPlatform.get());
        return seen;
    }

    /** The overlay's text, or {@code null} when none is bound; {@link ManifestOverlay#active} agrees. */
    private static @Nullable String read() {
        String text = ManifestOverlay.text(MANIFEST);
        assertThat(ManifestOverlay.active()).isEqualTo(text != null);
        return text;
    }
}
