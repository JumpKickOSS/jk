// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A worker's own downloads land on the run's board as rows, exactly as the engine's do. */
class PluginTransfersTest {

    private final FakeClock clock = new FakeClock();
    private final TransferBoard board = new TransferBoard(clock);
    private final PluginTransfers relay = new PluginTransfers(board);

    @Test
    void a_workers_body_is_a_row_while_it_streams_and_counts_once_it_lands() {
        relay.accept(
                "{\"t\":\"transfer\",\"id\":\"7:1\",\"e\":\"start\",\"label\":\"x.jar\",\"total\":100,\"artifact\":true}");
        relay.accept("{\"t\":\"transfer\",\"id\":\"7:1\",\"e\":\"read\",\"read\":40}");
        clock.advance(Duration.ofSeconds(1));

        TransferBoard.Snapshot mid = board.snapshot();
        assertThat(mid.inflight()).isEqualTo(1);
        assertThat(mid.rows()).singleElement().satisfies(r -> {
            assertThat(r.label()).isEqualTo("x.jar");
            assertThat(r.read()).isEqualTo(40);
            assertThat(r.total()).isEqualTo(100);
        });

        relay.accept("{\"t\":\"transfer\",\"id\":\"7:1\",\"e\":\"read\",\"read\":100}");
        relay.accept("{\"t\":\"transfer\",\"id\":\"7:1\",\"e\":\"done\"}");

        assertThat(board.snapshot().inflight()).isZero();
        assertThat(board.snapshot().files()).isEqualTo(1);
        assertThat(board.artifactsLanded()).isEqualTo(1);
    }

    @Test
    void a_worker_that_exits_mid_body_leaves_no_row_behind() {
        relay.accept("{\"t\":\"transfer\",\"id\":\"7:2\",\"e\":\"start\",\"label\":\"y.jar\",\"total\":-1}");
        assertThat(relay.open()).isEqualTo(1);

        relay.close();

        assertThat(board.snapshot().inflight()).isZero();
        assertThat(board.snapshot().files()).as("not landed").isZero();
    }

    @Test
    void a_malformed_or_unknown_reply_is_dropped() {
        relay.accept("{\"t\":\"transfer\"}");
        relay.accept("{\"t\":\"transfer\",\"id\":\"7:3\",\"e\":\"teleport\"}");
        relay.accept("{\"t\":\"transfer\",\"id\":\"7:4\",\"e\":\"read\",\"read\":5}");

        assertThat(board.snapshot().inflight()).isZero();
        assertThat(relay.open()).isZero();
    }
}
