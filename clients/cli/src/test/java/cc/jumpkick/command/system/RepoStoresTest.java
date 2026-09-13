// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The repository stores as {@code jk storage usage} and {@code jk doctor} print them: origin beside name. */
class RepoStoresTest {

    private static final String PRIVATE_ROW =
            "nexus.acme.com-0123456789ab|private|https://nexus.acme.com/maven|12|4096|ok";
    private static final String SHELF_ROW = "jk-local|jk-local||3|300|ok";
    private static final String LEGACY_ROW = "mirror|||7|700|legacy";

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(TestAnsi::strip).toList();
    }

    @Test
    void each_store_is_one_line_showing_the_origin_beside_the_name() {
        RepoStores.Stores stores =
                RepoStores.decode(CacheInventoryAck.repos(List.of(PRIVATE_ROW, SHELF_ROW, LEGACY_ROW)));

        assertThat(stores.error()).isNull();
        assertThat(stores.rows()).hasSize(3);
        List<String> lines = strip(RepoStores.render(stores, Theme.active()));
        assertThat(lines.get(0))
                .startsWith("repo:")
                .contains("private → https://nexus.acme.com/maven")
                .contains("repos/nexus.acme.com-0123456789ab")
                .contains("12 files");
        assertThat(lines.get(1)).contains("jk-local → first-party shelf");
        assertThat(lines.get(2))
                .startsWith("warn:")
                .contains("mirror — keyed by repository name, origin unknown")
                .contains("jk storage clean removes it");
    }

    @Test
    void the_json_member_carries_id_name_origin_and_state() {
        RepoStores.Stores stores = RepoStores.decode(CacheInventoryAck.repos(List.of(PRIVATE_ROW, LEGACY_ROW)));

        String json = RepoStores.json(stores);

        assertThat(json)
                .contains("\"id\":\"nexus.acme.com-0123456789ab\"")
                .contains("\"name\":\"private\"")
                .contains("\"origin\":\"https://nexus.acme.com/maven\"")
                .contains("\"state\":\"ok\"")
                .contains("\"state\":\"legacy\"");
    }

    @Test
    void an_engine_error_is_one_warning_line() {
        RepoStores.Stores stores = RepoStores.decode(CacheInventoryAck.error("engine unreachable"));

        assertThat(strip(RepoStores.render(stores, Theme.active())))
                .singleElement()
                .asString()
                .startsWith("warn:")
                .contains("engine unreachable");
        assertThat(RepoStores.json(stores)).contains("\"error\":\"engine unreachable\"");
    }
}
