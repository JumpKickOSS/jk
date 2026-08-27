// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import java.util.Optional;

/**
 * One process-global a test may not leak, and how to put it back.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so a global is registered
 * beside the code that owns it rather than in a central list that has to reach across modules —
 * {@code Theme}'s and {@code TerminalReflow}'s accessors are package-private, and a registry in
 * {@code :core} could not call them.
 *
 * <h2>Two capabilities, and globals that only have one</h2>
 *
 * Restoring is mandatory; reading is not. A memoized global like {@code TerminalReflow.cached} or
 * {@code Size} exposes a way to <em>drop</em> the memo but no way to read it, so it can be bounded
 * (the next test re-derives) without anyone being able to say whether a test changed it. Those
 * return {@link Optional#empty()} from {@link #capture()} and are restore-only: bounded, but not
 * attributable. Saying which is which is the point of the split — a guard that silently cannot see
 * half its registry is worse than one that says so.
 */
public interface BoundedGlobal {

    /** Greppable id, also the string {@code @InstallsGlobal} names. Lower-case, no spaces. */
    String name();

    /**
     * The current value, or empty when this global can be reset but not read. A returned value must
     * be safe to hold across a test and comparable with {@code equals} for attribution to work.
     */
    Optional<Object> capture();

    /**
     * Whether naming the test that changed this global is worth reporting.
     *
     * <p>False for a global that the production path under test writes <em>by design</em>, where
     * every test that exercises that path is a "culprit" and the report is noise. Measured on the
     * CLI integration tier: 48 classes and 324 methods write the session, because running any
     * command through the CLI entry point installs the resolved one. A guard that names 324
     * offenders has found one structural fact, not 324 defects.
     *
     * <p>Such a global is still bounded and restored — that is JK-1010's value and it does not
     * depend on attribution.
     */
    default boolean attributable() {
        return true;
    }

    /**
     * Put the global back. Receives whatever {@link #capture()} returned, or {@code null} for a
     * restore-only global — in which case this should drop the memo rather than set a value.
     */
    void restore(Object captured);
}
