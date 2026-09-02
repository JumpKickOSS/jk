// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store must not follow the cache root, or isolating a cache keeps discarding downloads.
 *
 * <p>A cleverer rule was tried first — redirect only when the cache root looks like the ambient
 * one, so a caller supplying its own directory keeps isolation — and it was measured failing. The
 * client resolves {@code JK_CACHE_DIR} to a concrete path and sends it; the engine daemon does not
 * inherit the client's environment, so its ambient root is {@code ~/.jk/cache}, nothing ever
 * matched, and every request kept its own store. The store helpers therefore take no cache
 * argument at all — an ignored parameter read as cache-rooted and produced a real mis-diagnosis.
 */
class JkStoresTest {

    @Test
    void store_and_store_cas_root_under_the_store_override(@TempDir Path tmp) {
        Path store = tmp.resolve("store");
        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        try {
            assertThat(JkStores.store()).isEqualTo(store);
            assertThat(JkStores.storeCas().root()).isEqualTo(store);
            assertThat(JkStores.resolve("git")).isEqualTo(store.resolve("git"));
            assertThat(JkStores.resolve("repos")).isEqualTo(store.resolve("repos"));
        } finally {
            if (prev == null) System.clearProperty("jk.env.JK_STORE_DIR");
            else System.setProperty("jk.env.JK_STORE_DIR", prev);
        }
    }

    @Test
    void cache_cas_is_rooted_at_the_cache_dir(@TempDir Path tmp) {
        Path cache = tmp.resolve("cache");
        assertThat(JkStores.cacheCas(cache).root()).isEqualTo(cache);
        assertThat(JkStores.cacheCas(cache)
                        .pathFor("abcd0123")
                        .getParent()
                        .getParent()
                        .getParent())
                .isEqualTo(cache.resolve("sha256"));
    }
}
