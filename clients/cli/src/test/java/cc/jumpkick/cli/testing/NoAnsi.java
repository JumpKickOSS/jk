// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import java.util.concurrent.Callable;

/**
 * Pins the render mode for the duration of a test body, in either direction.
 *
 * <p>A developer terminal is ANSI and CI is not, so an assertion about either mode that does not
 * pin it passes for the wrong reason on one of them. The mode is also process-global and mutable
 * ({@link SessionContext}'s static fallback, which the ordinary CLI command paths write), so a test
 * that merely reads it is at the mercy of whichever class ran before it in the same worker JVM.
 *
 * <p>Every method binds a {@link java.lang.ScopedValue} via {@link SessionContext#where}, which
 * out-ranks the process static for this thread and unbinds itself on return <em>and</em> on throw.
 * Nothing here writes the static: restoring a thread-scoped value onto a process-wide field is how
 * one thread's session used to reach every other.
 */
public final class NoAnsi {
    private NoAnsi() {}

    /** Runs {@code body} in plain mode — no ANSI, glyphs rewritten to ASCII. */
    public static <T> T forced(Callable<T> body) throws Exception {
        return withConfig(JkConfig.empty().withNoAnsi(true), body);
    }

    /**
     * Runs {@code body} in ANSI mode, out-ranking a {@code TERM=dumb} or {@code CI} environment
     * that would otherwise suppress it — the only way to assert ANSI output deterministically.
     */
    public static <T> T forcedAnsi(Callable<T> body) throws Exception {
        return withConfig(JkConfig.empty().withForceAnsi(true), body);
    }

    /** Runs {@code body} with progress rendering suppressed. */
    public static <T> T noProgress(Callable<T> body) throws Exception {
        return withConfig(JkConfig.empty().withNoProgress(true), body);
    }

    /** Runs {@code body} with {@code overlay} as the session config (replacing it, not merging). */
    public static <T> T withConfig(JkConfig overlay, Callable<T> body) throws Exception {
        return SessionContext.where(SessionContext.current().withConfig(overlay), body);
    }
}
