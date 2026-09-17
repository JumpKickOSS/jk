// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A walk on a virtual thread gives its carrier up as it goes; on a platform thread it never does.
 * Directory reads are native calls the scheduler does not compensate for, so a walk that kept its
 * carrier for its whole length would, with one walk per carrier, leave every other virtual thread
 * in the process unscheduled until the walks ended.
 */
class WalkCarrierShareTest {

    @Test
    void a_walk_on_a_virtual_thread_yields_its_carrier_as_it_goes(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp, 8, 80);
        AtomicInteger seen = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long before = PathUtil.carrierYields();
        Thread walker = Thread.ofVirtual().start(() -> {
            try {
                PathUtil.forEachRegularFile(root, (f, a) -> seen.incrementAndGet());
            } catch (IOException e) {
                failure.set(e);
            }
        });
        walker.join(Duration.ofSeconds(30).toMillis());
        assertThat(failure.get()).isNull();
        assertThat(seen.get()).isEqualTo(8 * 80);
        assertThat(PathUtil.carrierYields() - before)
                .as("one yield per %d entries", PathUtil.SHARE_EVERY)
                .isGreaterThanOrEqualTo((8 * 80) / PathUtil.SHARE_EVERY);
    }

    @Test
    void a_walk_on_a_platform_thread_never_yields(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp, 8, 80);
        AtomicInteger seen = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long before = PathUtil.carrierYields();
        Thread walker = Thread.ofPlatform().start(() -> {
            try {
                PathUtil.forEachRegularFile(root, (f, a) -> seen.incrementAndGet());
            } catch (IOException e) {
                failure.set(e);
            }
        });
        walker.join(Duration.ofSeconds(30).toMillis());
        assertThat(failure.get()).isNull();
        assertThat(seen.get()).isEqualTo(8 * 80);
        assertThat(PathUtil.carrierYields()).isEqualTo(before);
    }

    private static Path tree(Path tmp, int dirs, int filesEach) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        for (int d = 0; d < dirs; d++) {
            Path dir = Files.createDirectories(root.resolve("d" + d));
            for (int f = 0; f < filesEach; f++) Files.writeString(dir.resolve("f" + f + ".txt"), "x");
        }
        return root;
    }
}
