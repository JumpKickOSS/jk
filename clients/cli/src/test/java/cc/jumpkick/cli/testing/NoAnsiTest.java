// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import org.junit.jupiter.api.Test;

/**
 * The helper's own contract: a binding decides the mode, and the process static is never written.
 * These are the assertions the five hand-copied helpers could not make — each snapshotted {@link
 * SessionContext#current()} (scoped-or-static) and wrote it back to the static in a {@code finally}.
 */
class NoAnsiTest {

    @Test
    void a_forced_ansi_binding_outranks_a_plain_static() throws Exception {
        // Poison the process static in the plain direction, the way a leaked CLI path would.
        SessionContext.installConfig(JkConfig.empty().withNoAnsi(true));
        assertThat(Theme.active().isAnsi()).isFalse();

        assertThat(NoAnsi.forcedAnsi(() -> Theme.active().isAnsi()))
                .describedAs("the scoped binding decides, not the leaked static")
                .isTrue();

        // Leaving the scope restores nothing because nothing was overwritten.
        assertThat(Theme.active().isAnsi()).isFalse();
    }

    @Test
    void a_forced_plain_binding_outranks_an_ansi_static() throws Exception {
        SessionContext.installConfig(JkConfig.empty().withForceAnsi(true));
        assertThat(Theme.active().isAnsi()).isTrue();

        assertThat(NoAnsi.forced(() -> Theme.active().isAnsi())).isFalse();
    }

    @Test
    void the_helper_never_writes_the_process_static() throws Exception {
        Session before = SessionContext.installed();

        NoAnsi.forced(() -> Theme.active().isAnsi());
        NoAnsi.forcedAnsi(() -> Theme.active().isAnsi());
        NoAnsi.noProgress(() -> Theme.active().isAnsi());

        assertThat(SessionContext.installed())
                .describedAs("no method leaves a write behind on the shared static")
                .isSameAs(before);
    }
}
