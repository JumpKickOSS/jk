// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.DownloadsView;
import cc.jumpkick.cli.tui.LiveRegion;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.protocol.DownloadsEvent;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Any job's stream hands the run's download frames to the region on screen, never to the decoder. */
class WireStreamDownloadsTest {

    /** A region on screen that records what it was asked to draw. */
    private static final class Recording implements LiveRegion, DownloadsView {
        final List<TransferBoard.Snapshot> seen = new ArrayList<>();

        @Override
        public void downloads(TransferBoard.Snapshot snapshot) {
            seen.add(snapshot);
        }

        @Override
        public boolean renderCanceled() {
            return false;
        }
    }

    @Test
    void a_frame_reaches_the_region_and_the_decoder_sees_only_its_own_lines() throws Exception {
        var snapshot =
                new TransferBoard.Snapshot(1, 2, 1, 4_096L, 1, List.of(new TransferBoard.Row("g:a:1", 2_048L, 4_096L)));
        String stream = new DownloadsEvent(snapshot).encode() + "\n" + "{\"type\":\"terminal\"}\n";
        List<String> decoded = new ArrayList<>();
        var region = new Recording();
        LiveRegion.setActive(region);
        try {
            String result = WireStream.pumpRead(new BufferedReader(new StringReader(stream)), (type, line) -> {
                decoded.add(type);
                return "terminal".equals(type) ? "done" : null;
            });
            assertThat(result).isEqualTo("done");
        } finally {
            LiveRegion.clearActive(region);
        }
        assertThat(region.seen).containsExactly(snapshot);
        assertThat(decoded).containsExactly("terminal");
    }

    @Test
    void with_no_region_on_screen_a_frame_is_dropped_quietly() throws Exception {
        String stream = new DownloadsEvent(TransferBoard.Snapshot.EMPTY).encode() + "\n{\"type\":\"terminal\"}\n";

        String result = WireStream.pumpRead(
                new BufferedReader(new StringReader(stream)), (type, line) -> "terminal".equals(type) ? "done" : null);

        assertThat(result).isEqualTo("done");
    }
}
