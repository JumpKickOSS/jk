// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shared system terminal owns FD 0 and must be returned after a plan's key listener, never
 * closed — a closed FD 0 breaks {@code jk run} stdin, post-plan wizards, and the next plan's
 * Ctrl-O in the same invocation.
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

    @Test
    void restore_is_silent_when_the_shared_terminal_is_already_closed() throws Exception {
        Terminal first = Interactivity.takeSharedTerminal();
        try {
            Terminal t = dumbTerminal();
            Attributes saved = t.getAttributes();
            Interactivity.returnSharedTerminal(t);
            t.close();
            Interactivity.restoreOwnedAttributes(t, saved);
        } finally {
            if (first != null) Interactivity.returnSharedTerminal(first);
        }
    }

    @Test
    void restore_rewrites_attributes_when_the_shared_terminal_is_still_open() throws Exception {
        Terminal first = Interactivity.takeSharedTerminal();
        try {
            Terminal t = dumbTerminal();
            Attributes saved = t.getAttributes();
            boolean echoOn = saved.getLocalFlag(Attributes.LocalFlag.ECHO);
            Attributes muted = new Attributes(saved);
            muted.setLocalFlag(Attributes.LocalFlag.ECHO, !echoOn);
            t.setAttributes(muted);
            Interactivity.returnSharedTerminal(t);
            Interactivity.restoreOwnedAttributes(t, saved);
            assertThat(t.getAttributes().getLocalFlag(Attributes.LocalFlag.ECHO))
                    .isEqualTo(echoOn);
            t.close();
        } finally {
            if (first != null) Interactivity.returnSharedTerminal(first);
        }
    }
}
