// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionPromoteTest {

    @Test
    void promote_moves_blob_from_cache_to_store(@TempDir Path root) throws Exception {
        Cas cache = new Cas(root.resolve("cache"));
        Cas store = new Cas(root.resolve("store"));
        Path f = root.resolve("artifact.bin");
        Files.writeString(f, "heavy-payload");
        String sha = Hashing.sha256Hex(f);
        cache.putFile(f, sha);
        assertThat(Files.isRegularFile(cache.pathFor(sha))).isTrue();
        assertThat(Files.isRegularFile(store.pathFor(sha))).isFalse();

        var report = ActionPromote.promoteShas(cache, store, List.of(sha));

        assertThat(report.promoted()).isEqualTo(1);
        assertThat(Files.isRegularFile(store.pathFor(sha))).isTrue();
        assertThat(Files.isRegularFile(cache.pathFor(sha))).isFalse();
    }

    @Test
    void promote_files_puts_into_store_even_if_never_cached(@TempDir Path root) throws Exception {
        Cas cache = new Cas(root.resolve("cache"));
        Cas store = new Cas(root.resolve("store"));
        Path f = root.resolve("native");
        Files.writeString(f, "from-target");
        String sha = Hashing.sha256Hex(f);

        ActionPromote.promoteFiles(cache, store, List.of(f));

        assertThat(store.contains(sha)).isTrue();
    }
}
