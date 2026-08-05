// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkCacheConfig;
import org.junit.jupiter.api.Test;

/**
 * JK-1510: the artifact-store LRU eviction budget is opt-in. The 4 GiB display default must never
 * cue deletion of reachable store blobs; only an explicit {@code --max-size} or an explicitly
 * configured {@code max-store-size-mb} does.
 */
class CachePipelinesEvictionBudgetTest {

    @Test
    void default_store_budget_never_evicts() {
        assertThat(CachePipelines.storeEvictionBudgetBytes(null, JkCacheConfig.DEFAULTS))
                .isZero();
    }

    @Test
    void explicitly_configured_budget_evicts_to_it() {
        JkCacheConfig configured = new JkCacheConfig(true, 2048, 7, 30, 1024, true);
        assertThat(CachePipelines.storeEvictionBudgetBytes(null, configured))
                .isEqualTo(2048L * 1024 * 1024);
    }

    @Test
    void explicit_max_size_wins_over_config() {
        JkCacheConfig configured = new JkCacheConfig(true, 2048, 7, 30, 1024, true);
        assertThat(CachePipelines.storeEvictionBudgetBytes("1G", configured))
                .isEqualTo(1024L * 1024 * 1024);
        assertThat(CachePipelines.storeEvictionBudgetBytes("512M", JkCacheConfig.DEFAULTS))
                .isEqualTo(512L * 1024 * 1024);
    }
}
