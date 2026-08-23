// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Runs a body in plain mode — the state {@code CI=true}, {@code NO_COLOR}, {@code TERM=dumb} and
 * {@code --no-ansi} all put the CLI in, and the only one in which glyphs and the Unicode&rarr;ASCII
 * rewrite are deterministic. A developer terminal is ANSI, so an assertion about plain output that
 * does not force the mode passes for the wrong reason locally and fails in CI.
 */
public final class NoAnsi {
    private NoAnsi() {}

    /** Restores the previous session even when {@code body} throws. */
    public static <T> T forced(Callable<T> body) throws Exception {
        JkConfig noAnsi = JkConfig.empty().withNoAnsi(Optional.of(true));
        Session original = SessionContext.current();
        try {
            return SessionContext.where(original.withConfig(noAnsi), body);
        } finally {
            SessionContext.install(original);
        }
    }
}
