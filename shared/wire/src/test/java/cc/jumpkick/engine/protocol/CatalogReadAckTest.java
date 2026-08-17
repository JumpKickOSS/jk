// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogReadAckTest {

    @Test
    void encode_decode_round_trips_entries_and_layers() {
        CatalogReadAck ack = CatalogReadAck.of(
                List.of("warn-1"),
                List.of("project", "bundled"),
                List.of(
                        new CatalogReadAck.Entry(
                                "junit-jupiter",
                                "org.junit.jupiter",
                                "junit-jupiter",
                                "bundled",
                                List.of("6.1.0", "5.12.0")),
                        new CatalogReadAck.Entry("picocli", "info.picocli", "picocli", "project", List.of())));
        CatalogReadAck back = CatalogReadAck.decode(ack.encode());
        assertThat(back.error()).isNull();
        assertThat(back.warnings()).containsExactly("warn-1");
        assertThat(back.layerNames()).containsExactly("project", "bundled");
        assertThat(back.entries()).hasSize(2);
        assertThat(back.entries().getFirst().name()).isEqualTo("junit-jupiter");
        assertThat(back.entries().getFirst().moduleKey()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(back.entries().getFirst().cached()).containsExactly("6.1.0", "5.12.0");
        assertThat(back.entries().get(1).cached()).isEmpty();
    }

    @Test
    void error_ack_has_empty_rows() {
        CatalogReadAck back = CatalogReadAck.decode(CatalogReadAck.error("boom").encode());
        assertThat(back.error()).isEqualTo("boom");
        assertThat(back.entries()).isEmpty();
    }
}
