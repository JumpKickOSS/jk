// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The fcntl downcalls must actually link and round-trip (JK-2155). FD 0 in a test JVM is a
 * pipe or /dev/null, but fcntl F_GETFL/F_SETFL work on any fd kind, so the pulse path is
 * fully exercisable — only the Darwin/AArch64 variadic ABI itself needs macOS hardware.
 */
@DisabledOnOs(OS.WINDOWS)
class StdinWakeTest {

    @Test
    void fcntl_links_on_posix() {
        assertThat(StdinWake.availableForTest())
                .as("FFM fcntl downcall must link on %s", System.getProperty("os.name"))
                .isTrue();
    }

    @Test
    void get_flags_succeeds_and_pulse_restores_them() {
        int before = StdinWake.currentFlagsForTest();
        assertThat(before).as("F_GETFL on FD 0").isNotNegative();

        StdinWake.pulseNonBlocking();

        int after = StdinWake.currentFlagsForTest();
        assertThat(after).as("pulse must restore the original flags").isEqualTo(before);
    }

    @Test
    void o_nonblock_constant_matches_platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Sanity anchor for the hardcoded constants: Darwin/BSD 0x4, Linux 0x800.
        boolean darwinish = os.contains("mac") || os.contains("darwin") || os.contains("bsd");
        int expected = darwinish ? 0x0004 : 0x800;
        // The pulse ORs the constant in and strips it back out; a wrong constant would make
        // get_flags_succeeds_and_pulse_restores_them pass trivially, so pin it explicitly.
        assertThat(StdinWake.oNonblockForTest()).isEqualTo(expected);
    }
}
