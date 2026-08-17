// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * JK-2086: the shared system terminal owns FD 0 and must be RETURNED after a plan's key
 * listener, never closed — a closed FD 0 broke {@code jk run} stdin, post-plan wizards, and the
 * next plan's Ctrl-O in the same invocation.
 */
class InteractivityTest {

    @AfterEach
    void drainSharedSlot() {
        // Leave no test terminal behind for other tests.
        Interactivity.takeSharedTerminal();
    }

    private static Terminal dumbTerminal() throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
    }

    @Test
    void returned_terminal_is_handed_out_again() throws Exception {
        Terminal first = Interactivity.takeSharedTerminal(); // drain whatever the probe left
        try {
            Terminal t = dumbTerminal();
            Interactivity.returnSharedTerminal(t);
            assertThat(Interactivity.takeSharedTerminal()).isSameAs(t);
            assertThat(Interactivity.takeSharedTerminal()).isNull();
            t.close();
        } finally {
            if (first != null) Interactivity.returnSharedTerminal(first);
        }
    }

    @Test
    void return_is_idempotent_and_never_replaces_an_existing_shared_terminal() throws Exception {
        Terminal first = Interactivity.takeSharedTerminal();
        try {
            Terminal a = dumbTerminal();
            Terminal b = dumbTerminal();
            Interactivity.returnSharedTerminal(a);
            Interactivity.returnSharedTerminal(a); // double return: no-op
            Interactivity.returnSharedTerminal(b); // occupied slot: b is NOT installed, NOT closed
            assertThat(Interactivity.takeSharedTerminal()).isSameAs(a);
            assertThat(Interactivity.takeSharedTerminal()).isNull();
            // b must still be usable (never closed by returnSharedTerminal).
            b.writer().print("");
            a.close();
            b.close();
            Interactivity.returnSharedTerminal(null); // null tolerated
        } finally {
            if (first != null) Interactivity.returnSharedTerminal(first);
        }
    }
}
