// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model.command;

/**
 * jk's process exit-code vocabulary — the single documented source of truth so command bodies use a
 * self-describing name instead of a bare integer whose meaning lived only in a {@code // EX_USAGE}
 * comment. Values follow {@code sysexits.h} where one applies; {@code 0}/{@code 1}/{@code 2} keep
 * their conventional/jk meanings and {@code 130} is the shell's {@code 128 + SIGINT}.
 *
 * <p>Adoption is gradual: {@code 0} and {@code 1} are universally understood and often stay bare;
 * the higher sysexits codes are the ones a name genuinely clarifies. A hard process exit is the one
 * place where even {@code 0} and {@code 1} must be named — {@code System.exit(<literal>)} and
 * {@code Runtime.getRuntime().halt(<literal>)} are banned by guard G2, because those two calls are
 * the only ones whose integer a user's script actually sees.
 *
 * <p>Every value means exactly one thing. Before the integer {@code 2} meant eight: a bad
 * {@code jk.toml}, a wrong command line, a missing spec file, an unexpected {@code Throwable}, an
 * engine that could not be reached, a watchdog timeout — and, from {@code GlobalCancel}, that the
 * user had pressed Ctrl-C. No script could branch on it. Usage errors were already {@code 64} at 84
 * production sites and {@code 2} at 18, so the 18 moved; cancellation moved to {@link #INTERRUPTED}.
 */
public final class Exit {

    private Exit() {}

    /** Success. */
    public static final int SUCCESS = 0;

    /** General runtime failure (a build/operation ran but did not succeed). */
    public static final int FAILURE = 1;

    /** Bad project/config input: no {@code jk.toml}/{@code jk-lock.toml}, or an invalid argument value. */
    public static final int CONFIG = 2;

    /** The build ran and its tests did not all pass. */
    public static final int TESTS_FAILED = 4;

    /** {@code EX_USAGE}: the command line itself was wrong (missing/unknown args). */
    public static final int USAGE = 64;

    /** {@code EX_DATAERR}: input data was present but malformed (e.g. no main class to run). */
    public static final int DATA_ERR = 65;

    /** {@code EX_NOINPUT}: a required input file is missing or unreadable. */
    public static final int NO_INPUT = 66;

    /** {@code EX_SOFTWARE}: an internal error the user cannot act on. */
    public static final int SOFTWARE = 70;

    /** {@code EX_CANTCREAT}: an output file could not be created (would overwrite, permissions). */
    public static final int CANT_CREATE = 73;

    /**
     * {@code 128 + SIGINT}: the user interrupted the run (Ctrl-C), or a wait for it was interrupted.
     * The shell's own convention, so {@code $?} after Ctrl-C reads the same from jk as from any
     * other tool. Reserved for a real interrupt: a job the engine abandoned because the engine died
     * is {@link #SOFTWARE}, not this.
     */
    public static final int INTERRUPTED = 130;
}
