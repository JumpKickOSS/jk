// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.wire.protocol.DownloadsEvent;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A JSON command's downloads: a record per burst start, per period, and per burst end. */
class JsonlDownloadsTest {

    private final FakeClock clock = new FakeClock();
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
    private final JsonlDownloads view = new JsonlDownloads(() -> out, clock);

    private static TransferBoard.Snapshot streaming(int settled, long bytes) {
        return new TransferBoard.Snapshot(settled, 183, settled, bytes, 3, List.of());
    }

    private List<DownloadsEvent> lines() {
        return buf.toString(StandardCharsets.UTF_8)
                .lines()
                .map(DownloadsEvent::decode)
                .toList();
    }

    @Test
    void a_burst_is_said_when_it_starts_on_the_period_and_when_it_ends() {
        view.downloads(streaming(3, 1_000));
        view.downloads(streaming(10, 9_000)); // within the period: nothing
        clock.advance(Duration.ofMillis(JsonlDownloads.PERIOD_MILLIS));
        view.downloads(streaming(90, 70_000));
        view.downloads(new TransferBoard.Snapshot(0, 0, 183, 150_000, 0, List.of())); // the plan closed

        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\"type\":\"" + EngineProtocol.DOWNLOADS + "\"");
        assertThat(lines())
                .extracting(e -> e.snapshot().settled(), e -> e.snapshot().bytes(), DownloadsEvent::done)
                .containsExactly(tuple(3, 1_000L, false), tuple(90, 70_000L, false), tuple(0, 150_000L, true));
        assertThat(lines().getLast().snapshot().files()).isEqualTo(183);
    }

    @Test
    void a_run_that_moves_no_bytes_writes_nothing() {
        view.downloads(new TransferBoard.Snapshot(150, 183, 0, 0L, 0, List.of()));
        view.downloads(TransferBoard.Snapshot.EMPTY);

        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** A download too quick for any frame to see it streaming still gets its summary. */
    @Test
    void a_burst_seen_only_in_its_final_frame_is_still_summed_up() {
        view.downloads(new TransferBoard.Snapshot(0, 0, 2, 4_096, 0, List.of()));
        view.downloads(new TransferBoard.Snapshot(0, 0, 2, 4_096, 0, List.of())); // the same end again

        assertThat(lines()).singleElement().satisfies(line -> {
            assertThat(line.done()).isTrue();
            assertThat(line.snapshot().files()).isEqualTo(2);
            assertThat(line.snapshot().bytes()).isEqualTo(4_096);
        });
    }

    @Test
    void finishing_the_command_closes_a_burst_left_open() {
        view.downloads(streaming(3, 1_000));
        view.finish();

        assertThat(lines()).extracting(DownloadsEvent::done).containsExactly(false, true);
    }

    /** The terminal is the stream's last line: a burst open at it closes first, and nothing follows. */
    @Test
    void sealing_closes_an_open_burst_and_writes_nothing_after() {
        view.downloads(streaming(3, 1_000));
        view.seal();
        view.downloads(new TransferBoard.Snapshot(0, 0, 183, 150_000, 0, List.of())); // after the terminal
        view.finish();

        assertThat(lines())
                .extracting(e -> e.snapshot().bytes(), DownloadsEvent::done)
                .containsExactly(tuple(1_000L, false), tuple(1_000L, true));
    }
}
