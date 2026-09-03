// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.CatalogReadAck;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogReadOpsTest {

    @Test
    void bundled_list_includes_junit_jupiter() {
        CatalogReadAck ack = CatalogReadOps.read(
                new CatalogReadOps.Request(Path.of("."), null, null, "list", List.of(), false, false, true));
        assertThat(ack.error()).isNull();
        assertThat(ack.entries().stream().map(CatalogReadAck.Entry::name)).contains("junit-jupiter");
        assertThat(ack.layerNames()).contains("bundled");
    }

    @Test
    void search_matches_name_substring(@TempDir Path cache) {
        CatalogReadAck ack = CatalogReadOps.read(
                new CatalogReadOps.Request(Path.of("."), cache, cache, "search", List.of("junit"), false, true, true));
        assertThat(ack.entries().stream().map(CatalogReadAck.Entry::name))
                .contains("junit-jupiter", "junit-platform-launcher");
    }

    @Test
    void search_and_semantics(@TempDir Path cache) {
        CatalogReadAck ack = CatalogReadOps.read(new CatalogReadOps.Request(
                Path.of("."), cache, cache, "search", List.of("spring", "starter"), false, false, true));
        assertThat(ack.entries().stream().map(CatalogReadAck.Entry::name)).contains("spring-boot-starter");
    }

    @Test
    void offline_without_cached_versions_is_empty(@TempDir Path cache) {
        CatalogReadAck ack = CatalogReadOps.read(
                new CatalogReadOps.Request(Path.of("."), cache, cache, "search", List.of("junit"), true, true, true));
        assertThat(ack.entries()).isEmpty();
    }
}
