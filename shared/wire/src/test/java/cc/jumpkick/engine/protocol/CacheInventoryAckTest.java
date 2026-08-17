// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CacheInventoryAckTest {

    @Test
    void usage_round_trips() {
        CacheInventoryAck ack = CacheInventoryAck.usage("usage", List.of("classFiles|2|40"), 10, 99);
        CacheInventoryAck back = CacheInventoryAck.decode(ack.encode());
        assertThat(back.query()).isEqualTo("usage");
        assertThat(back.stats()).containsExactly("classFiles|2|40");
        assertThat(back.totalFiles()).isEqualTo(10);
        assertThat(back.totalBytes()).isEqualTo(99);
    }

    @Test
    void repo_search_and_wipe_round_trip() {
        CacheInventoryAck search = CacheInventoryAck.repoSearch(List.of("g|a|1.0,2.0"));
        assertThat(CacheInventoryAck.decode(search.encode()).entries()).containsExactly("g|a|1.0,2.0");
        CacheInventoryAck wipe = CacheInventoryAck.wipe(3, 12);
        CacheInventoryAck back = CacheInventoryAck.decode(wipe.encode());
        assertThat(back.files()).isEqualTo(3);
        assertThat(back.bytes()).isEqualTo(12);
    }
}
