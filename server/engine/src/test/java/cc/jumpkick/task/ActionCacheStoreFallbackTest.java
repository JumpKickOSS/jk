// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionPromote;
import cc.jumpkick.cache.Cas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionCacheStoreFallbackTest {

    @Test
    void restore_hits_store_cas_after_promote(@TempDir Path root) throws Exception {
        Path cacheRoot = root.resolve("cache");
        Path storeRoot = root.resolve("store");
        Cas cacheCas = new Cas(cacheRoot);
        Cas storeCas = new Cas(storeRoot);
        ActionCache ac = new ActionCache(cacheCas, cacheRoot.resolve("actions"), storeCas);

        Path base = root.resolve("target");
        Files.createDirectories(base);
        Path bin = base.resolve("app");
        Files.writeString(bin, "native-v1");
        var rec = ac.storeArtifacts("native-image@m", "k1", Map.of(), base, List.of(bin));
        String sha = rec.outputs().values().iterator().next();

        // Promote out of cache CAS — blob only in store
        ActionPromote.promoteShas(cacheCas, storeCas, List.of(sha));
        assertThat(Files.isRegularFile(cacheCas.pathFor(sha))).isFalse();
        assertThat(Files.isRegularFile(storeCas.pathFor(sha))).isTrue();

        Files.deleteIfExists(bin);
        assertThat(ac.restoreArtifacts(rec, base)).isTrue();
        assertThat(Files.readString(bin)).isEqualTo("native-v1");
    }
}
