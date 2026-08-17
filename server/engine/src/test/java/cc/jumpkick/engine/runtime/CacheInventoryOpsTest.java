// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.CacheInventoryAck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheInventoryOpsTest {

    @Test
    void usage_of_empty_cache_is_zero(@TempDir Path cache) throws Exception {
        Files.createDirectories(cache);
        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("usage", cache, null, List.of(), List.of(), false));
        assertThat(ack.error()).isNull();
        assertThat(ack.query()).isEqualTo("usage");
        assertThat(ack.totalFiles()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void wipe_store_counts_and_removes(@TempDir Path store) throws Exception {
        Path child = store.resolve("blob");
        Files.createDirectories(store);
        Files.writeString(child, "abc");
        CacheInventoryAck dry = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), true));
        assertThat(dry.files()).isEqualTo(1);
        assertThat(Files.exists(child)).isTrue();
        CacheInventoryAck wipe = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), false));
        assertThat(wipe.files()).isEqualTo(1);
        assertThat(Files.exists(child)).isFalse();
    }

    @Test
    void unknown_query_is_an_error() throws Exception {
        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("nope", null, null, List.of(), List.of(), false));
        assertThat(ack.error()).contains("unknown");
    }
}
