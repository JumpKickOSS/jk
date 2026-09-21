// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import org.junit.jupiter.api.Test;

/**
 * What Ctrl-C leaves on the terminal. The handler ends in {@link Runtime#halt}, which skips the
 * dispatch return that closes a command's blank-line envelope, so the settle has to print the
 * closing blank itself — otherwise the cancel line butts straight against the next shell prompt
 * while every other way a command ends is followed by a gap.
 */
class GlobalCancelSettleTest {

    @Test
    void a_cancelled_plan_ends_with_the_envelope_blank() {
        CliOutput.beginCommand(false);
        Capture.Streams streams = Capture.both(() -> {
            JkManager.plan(System.out, "Install", true);
            GlobalCancel.settleAsCanceled();
        });

        String visible = TestAnsi.strip(streams.out());
        assertThat(visible).contains("Install").contains("job was cancelled by user");
        assertThat(visible).endsWith("\n\n");
        assertThat(visible).doesNotEndWith("\n\n\n");
    }

    @Test
    void a_cancel_with_no_live_region_ends_with_the_envelope_blank() {
        CliOutput.beginCommand(false);
        Capture.Streams streams = Capture.both(GlobalCancel::settleAsCanceled);

        // Leading blank, the notice, closing blank — all on stderr, the stream that wrote it.
        assertThat(TestAnsi.strip(streams.err())).isEqualTo("\n✘ Build job was cancelled\n\n");
        assertThat(streams.out()).isEmpty();
    }
}
