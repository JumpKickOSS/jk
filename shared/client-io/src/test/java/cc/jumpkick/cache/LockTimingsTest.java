// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.SysProps;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The ledger under test is this class's own: {@code lock-timings.toml} lives under the state root,
 * and the rates it asserts are exact, so the state root is a throwaway rather than the one a real
 * lock — or the engine running this suite — has been writing to.
 */
@SysProps.TempRoots("jk.env.JK_STATE_DIR")
class LockTimingsTest {

    @Test
    void trimmed_mean_drops_deciles() {
        List<Long> samples = LongStream.rangeClosed(1, 10).boxed().toList();
        assertThat(LockTimings.trimmedMean(samples)).isEqualTo(5);
    }

    @Test
    void estimate_scales_with_package_count() {
        // Pure composition from cold defaults — larger graphs cost more.
        long small = LockTimings.estimateMillis(2, 10);
        long large = LockTimings.estimateMillis(50, 400);
        assertThat(large).isGreaterThan(small);
        assertThat(small).isGreaterThanOrEqualTo(200);
    }

    @Test
    void estimate_uses_known_packages_over_declared_expansion() {
        // knownPackages=5 should not expand declared*10.
        long withKnown = LockTimings.estimateMillis(100, 5);
        long withoutKnown = LockTimings.estimateMillis(100, 0);
        assertThat(withoutKnown).isGreaterThan(withKnown);
    }

    @Test
    void record_persists_atomized_rates() {
        LockTimings.clearMemo();
        // graph 1000ms / 10 pkgs = 100; mat 200ms / 10 = 20; total 1500 → overhead 300
        LockTimings.record(1000, 10, 200, 10, 1500);
        assertThat(LockTimings.graphPerPackageMs()).isEqualTo(100);
        assertThat(LockTimings.materializePerPackageMs()).isEqualTo(20);
        assertThat(LockTimings.overheadMs()).isEqualTo(300);
        assertThat(Files.isRegularFile(LockTimings.defaultFile())).isTrue();
    }
}
