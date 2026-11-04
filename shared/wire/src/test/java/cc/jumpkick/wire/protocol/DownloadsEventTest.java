// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.task.TransferBoard;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownloadsEventTest {

    @Test
    void round_trips_counts_and_rows() {
        var snapshot = new TransferBoard.Snapshot(
                143,
                412,
                120,
                40_000_000L,
                37,
                List.of(
                        new TransferBoard.Row("com.google.guava:guava:33.0.0-jre", 1_036_000L, 3_047_503L),
                        new TransferBoard.Row("io.netty:netty-codec-http:4.1.112.Final · pom", 1_468_006L, -1L)));
        String line = new DownloadsEvent(snapshot).encode();

        assertThat(Jsonl.str(line, "type")).isEqualTo(EngineProtocol.DOWNLOADS);
        assertThat(DownloadsEvent.decode(line).snapshot()).isEqualTo(snapshot);
    }

    @Test
    void a_label_with_json_syntax_survives() {
        var snapshot =
                new TransferBoard.Snapshot(0, 1, 0, 0L, 1, List.of(new TransferBoard.Row("a\"b}{c:d,[e]", 5L, 10L)));

        assertThat(DownloadsEvent.decode(new DownloadsEvent(snapshot).encode()).snapshot())
                .isEqualTo(snapshot);
    }

    @Test
    void the_empty_board_round_trips() {
        assertThat(DownloadsEvent.decode(new DownloadsEvent(TransferBoard.Snapshot.EMPTY).encode())
                        .snapshot())
                .isEqualTo(TransferBoard.Snapshot.EMPTY);
    }
}
