// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A status walks the cache once per ten minutes, and again after a prune. */
class StatusCacheUsageTest {

    private static final class FakeClock implements Clock {
        long now = 1_800_000_000_000L;

        @Override
        public long millis() {
            return now;
        }

        @Override
        public long nanos() {
            return now * 1_000_000L;
        }
    }

    @Test
    void a_second_status_reuses_the_walk_until_it_is_ten_minutes_old(@TempDir Path cache) throws Exception {
        FakeClock clock = new FakeClock();
        blob(cache, 1024);
        assertThat(StatusCacheUsage.of(cache, clock).root().bytes()).isEqualTo(1024);

        blob(cache, 2048);
        clock.now += 60_000;
        CacheCommand.SectionStats kept = StatusCacheUsage.of(cache, clock);
        assertThat(kept.cacheCas().files())
                .as("a minute later: the remembered walk")
                .isEqualTo(1);
        assertThat(kept.root().bytes()).isEqualTo(1024);

        clock.now += StatusCacheUsage.FRESH_MILLIS;
        CacheCommand.SectionStats walked = StatusCacheUsage.of(cache, clock);
        assertThat(walked.cacheCas().files())
                .as("past the freshness window: walked again")
                .isEqualTo(2);
        assertThat(walked.root().bytes()).isGreaterThanOrEqualTo(1024 + 2048);
    }

    @Test
    void a_prune_since_the_walk_walks_again(@TempDir Path cache) throws Exception {
        FakeClock clock = new FakeClock();
        Path one = blob(cache, 4096);
        assertThat(StatusCacheUsage.of(cache, clock).cacheCas().files()).isEqualTo(1);

        Files.delete(one);
        Path stamp = CacheTree.LAST_PRUNED.under(cache);
        Files.writeString(stamp, "x");
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(clock.now + 1));
        clock.now += 2;

        assertThat(StatusCacheUsage.of(cache, clock).cacheCas().files()).isZero();
    }

    @Test
    void a_nuke_since_the_walk_walks_again(@TempDir Path cache) throws Exception {
        FakeClock clock = new FakeClock();
        Path one = blob(cache, 4096);
        assertThat(StatusCacheUsage.of(cache, clock).cacheCas().files()).isEqualTo(1);

        // A nuke empties the tiers and leaves bookkeeping: the CAS shard goes with its contents.
        Files.delete(one);
        Files.delete(one.getParent());
        Files.setLastModifiedTime(CacheTree.CACHE_CAS.under(cache), FileTime.fromMillis(42));
        clock.now += 1_000;

        assertThat(StatusCacheUsage.of(cache, clock).cacheCas().files()).isZero();
    }

    private static Path blob(Path cache, int size) throws Exception {
        Path dir = Files.createDirectories(CacheTree.CACHE_CAS.under(cache).resolve("ab"));
        return Files.write(Files.createTempFile(dir, "b", ""), new byte[size]);
    }
}
