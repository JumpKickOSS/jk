// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How often each tier paid for itself, counted in memory and carried across engines in the state directory. */
class CacheStatsTest {

    private static final String SHA = "a".repeat(64);

    @BeforeEach
    @AfterEach
    void reset() {
        CacheStats.resetForTest();
    }

    @Test
    void a_record_is_memo_heavy_or_action_by_what_it_names() {
        assertThat(CacheStats.classify("run-tests@x", Map.of("tests.total", "12"), s -> 0))
                .isEqualTo(CacheStats.Tier.MEMO);
        assertThat(CacheStats.classify("compile-main@x", Map.of("A.class", SHA), s -> 900))
                .isEqualTo(CacheStats.Tier.ACTION);
        assertThat(CacheStats.classify("compile-main@x", Map.of("big.bin", SHA), s -> 64L << 20))
                .as("any blob of 32 MiB or more")
                .isEqualTo(CacheStats.Tier.HEAVY);
        assertThat(CacheStats.classify("package-assembly@x", Map.of("app.jar", SHA), s -> 1 << 20))
                .as("a Class-C task whatever its size")
                .isEqualTo(CacheStats.Tier.HEAVY);
    }

    @Test
    void the_totals_survive_a_fold_and_keep_counting_after_it(@TempDir Path state) {
        Path file = state.resolve(CacheStats.FILE_NAME);
        CacheStats.hit(CacheStats.Tier.ACTION, 1000);
        CacheStats.hit(CacheStats.Tier.ACTION, 500);
        CacheStats.store(CacheStats.Tier.ACTION, 2000);
        CacheStats.store(CacheStats.Tier.HEAVY, 64L << 20);

        CacheStats.flush(file);
        CacheStats.hit(CacheStats.Tier.ACTION, 100);
        CacheStats.Snapshot s = CacheStats.snapshot(file);

        CacheStats.Row action = s.rows().get(CacheStats.Tier.ACTION.ordinal());
        assertThat(action.hits()).as("two folded, one live").isEqualTo(3);
        assertThat(action.stores()).isEqualTo(1);
        assertThat(action.restoredBytes()).isEqualTo(1600);
        assertThat(action.hitRate()).isEqualTo(0.75);
        assertThat(s.rows().get(CacheStats.Tier.HEAVY.ordinal()).storedBytes()).isEqualTo(64L << 20);
        assertThat(s.rows().get(CacheStats.Tier.MEMO.ordinal()).hitRate())
                .as("nothing yet")
                .isEqualTo(-1);

        CacheStats.flush(file);
        long since = s.sinceMillis();
        CacheStats.resetForTest();
        CacheStats.Snapshot reread = CacheStats.snapshot(file);
        assertThat(reread.rows().get(CacheStats.Tier.ACTION.ordinal()).hits())
                .as("a new engine reads them back")
                .isEqualTo(3);
        assertThat(reread.sinceMillis()).isEqualTo(since);
    }
}
