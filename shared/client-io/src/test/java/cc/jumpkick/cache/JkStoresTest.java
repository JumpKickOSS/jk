// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the store must not follow the cache root, or isolating a cache keeps discarding downloads.
 *
 * <p>A cleverer rule was tried first — redirect only when the cache root looks like the ambient one, so
 * a caller supplying its own directory keeps isolation — and it was measured failing. The client
 * resolves {@code JK_CACHE_DIR} to a concrete path and sends it; the engine daemon does not inherit the
 * client's environment, so its ambient root is {@code ~/.jk/cache}, nothing ever matched, and every
 * request kept its own store.
 */
class JkStoresTest {

    @Test
    void the_store_is_independent_of_whatever_cache_root_arrives(@TempDir Path tmp) {
        Path store = tmp.resolve("store");

        for (Path cacheRoot : new Path[] {
            tmp.resolve("cache"), // the ambient one
            tmp.resolve("fresh-cache-for-this-test"), // JK_CACHE_DIR pointed somewhere new
            tmp.resolve("some-fixture-cache"), // a fixture supplying its own
            null
        }) {
            assertThat(JkStores.storeRootFor(cacheRoot, tmp.resolve("cache"), store))
                    .as("cacheRoot=%s", cacheRoot)
                    .isEqualTo(store);
        }
    }

    @Test
    void a_store_subdirectory_resolves_under_the_store(@TempDir Path tmp) {
        Path store = tmp.resolve("store");

        assertThat(JkStores.storeRootFor(tmp.resolve("anything"), tmp.resolve("cache"), store)
                        .resolve("git"))
                .isEqualTo(store.resolve("git"));
    }
}
