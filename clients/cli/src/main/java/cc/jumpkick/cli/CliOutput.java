// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.tui.PlainAscii;
import java.io.PrintStream;

/**
 * The CLI's single user-facing output seam. Commands write results and diagnostics through here
 * rather than touching {@link System#out}/{@link System#err} directly, so there is one place that
 * owns "what the client prints" — the client-side counterpart to the engine routing everything
 * through {@code PipelineListener}/{@code StepContext}. Behaviour is a thin pass-through today
 * (stdout for results, stderr for diagnostics/errors/progress); centralising it means future
 * concerns (capture for tests, a global quiet gate, alternate transports) have a single owner.
 *
 * <p>Convention, matching the streams it wraps: {@link #out} for machine/user <em>result</em> output
 * on stdout; {@link #err} for human-facing diagnostics, errors, and progress on stderr.
 *
 * <p>Under {@code --no-ansi}, string writes run through {@link PlainAscii} so ellipsis, bullets,
 * and pulse circles in free-form messages become ASCII without each command hand-substituting.
 */
public final class CliOutput {

    private CliOutput() {}

    /** Print a line to stdout (result output). */
    public static void out(String line) {
        System.out.println(PlainAscii.apply(line));
    }

    /** Print a blank line to stdout. */
    public static void out() {
        System.out.println();
    }

    /** Print to stdout with no trailing newline. */
    public static void outRaw(String s) {
        System.out.print(PlainAscii.apply(s));
    }

    /** Print a line to stderr (diagnostics, errors, progress). */
    public static void err(String line) {
        System.err.println(PlainAscii.apply(line));
    }

    /** Print a blank line to stderr. */
    public static void err() {
        System.err.println();
    }

    /** Print to stderr with no trailing newline. */
    public static void errRaw(String s) {
        System.err.print(PlainAscii.apply(s));
    }

    /**
     * Stdout for APIs that need a {@link PrintStream} (CommandManager, Spinner, renderers). Under
     * plain mode the stream rewrites Unicode chrome via {@link PlainAscii#wrap}.
     */
    public static PrintStream stdout() {
        return PlainAscii.wrap(System.out);
    }

    /**
     * Stderr for APIs that need a {@link PrintStream}. Under plain mode the stream rewrites
     * Unicode chrome via {@link PlainAscii#wrap}.
     */
    public static PrintStream stderr() {
        return PlainAscii.wrap(System.err);
    }
}
