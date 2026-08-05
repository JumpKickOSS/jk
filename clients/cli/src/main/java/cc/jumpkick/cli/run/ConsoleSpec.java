// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
// Theme used for ANSI styling of took / errors
import cc.jumpkick.run.BuildPlanResult;
import java.time.Duration;
import java.util.function.Function;

/**
 * Console presentation for a simple-task command: spinner label plus success/failure tails from
 * {@link BuildPlanResult}. Optional {@code softFailure} forces a failure chip after a successful
 * pipeline (e.g. {@code jk run} with nothing executable). Duration suffix is appended by the framework.
 */
public record ConsoleSpec(
        String command,
        Function<BuildPlanResult, String> onSuccess,
        Function<BuildPlanResult, String> onFailure,
        boolean chip,
        boolean exec,
        Function<BuildPlanResult, String> softFailure) {

    /** Default generic success/failure finish ({@code chip}/{@code exec} false). */
    public ConsoleSpec(
            String command, Function<BuildPlanResult, String> onSuccess, Function<BuildPlanResult, String> onFailure) {
        this(command, onSuccess, onFailure, false, false);
    }

    public ConsoleSpec(
            String command,
            Function<BuildPlanResult, String> onSuccess,
            Function<BuildPlanResult, String> onFailure,
            boolean chip) {
        this(command, onSuccess, onFailure, chip, false);
    }

    public ConsoleSpec(
            String command,
            Function<BuildPlanResult, String> onSuccess,
            Function<BuildPlanResult, String> onFailure,
            boolean chip,
            boolean exec) {
        this(command, onSuccess, onFailure, chip, exec, null);
    }

    /**
     * Duration suffix for settle lines. ANSI: dim italic {@code took Xms}. Plain ({@code
     * --no-ansi}): {@code - took Xms} so the dash substitutes for color separation.
     */
    public static String took(Duration d) {
        String body = "took " + fmtDuration(d);
        if (!Theme.active().isAnsi()) {
            return "- " + body;
        }
        return Theme.colorize(body, Theme.active().darkGray().italic());
    }

    /**
     * A diagnostic error line: red {@code ‼ Error} (or plain {@code ! Error}), the step in
     * brackets, then the message on its own line.
     */
    public static String errorLine(String step, String message) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return Glyphs.CROSS_PLAIN + " Error [" + step + "]:" + System.lineSeparator() + message;
        }
        return Theme.colorize(Glyphs.CROSS + " Error", t.error())
                + " ["
                + step
                + "]:"
                + System.lineSeparator()
                + message;
    }

    /** Render an error diagnostic for the console, per its {@code code}. */
    public static String renderError(BuildPlanResult.Diagnostic d) {
        return renderError(d.step(), d.code(), d.message());
    }

    /** Render an error diagnostic from its parts (used by live + summary paths alike). */
    public static String renderError(String step, String code, String message) {
        if ("verbatim".equals(code)) return message;
        if (isCompilerCode(code)) return CompilerDiagnostic.render(message);
        return errorLine(step, message);
    }

    /** Render a warning diagnostic for the console, per its {@code code}. */
    public static String renderWarning(BuildPlanResult.Diagnostic d) {
        if (isCompilerCode(d.code())) return compilerWarning(d.step(), d.message());
        return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning()) + " [" + d.step() + "]: "
                + d.message();
    }

    /**
     * A compiler warning: a yellow {@code ‼ Warning [step]:} header, then the compiler's verbatim
     * block colorized like an error (relative paths, etc.).
     */
    public static String compilerWarning(String step, String message) {
        return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning())
                + " ["
                + step
                + "]:"
                + System.lineSeparator()
                + CompilerDiagnostic.render(message);
    }

    /** Compiler diagnostics (javac/kotlinc) carry a verbatim multi-line block. */
    public static boolean isCompilerCode(String code) {
        return "javac".equals(code) || "kotlinc".equals(code);
    }

    /**
     * Formats a {@code [k of N]} counter with darkGray brackets and a zero-padded numerator.
     * Used in build completion lines and similar indexed output.
     *
     * <p>Example: {@code countBracket(1, 16, theme)} → {@code "[01 of 16]"} with dark-gray brackets.
     */
    public static String countBracket(int n, int total, Theme t) {
        String num = String.format("%0" + Integer.toString(total).length() + "d", n);
        return Theme.colorize("[", t.darkGray()) + num + " of " + total + Theme.colorize("]", t.darkGray());
    }

    /**
     * Formats an absolute {@code [N]} counter with darkGray brackets — no denominator. Used when
     * the total is not known ahead of time (e.g. {@code jk lock} dependency resolution).
     *
     * <p>Example: {@code countBracket(42, theme)} → {@code "[42]"} with dark-gray brackets.
     */
    public static String countBracket(int n, Theme t) {
        return Theme.colorize("[", t.darkGray()) + n + Theme.colorize("]", t.darkGray());
    }

    /**
     * Human-friendly duration: {@code 712ms}, {@code 3.1s}, {@code 2m 4s}, {@code 1h 3m 2s}, {@code
     * 1d 12h 13m 5s}.
     */
    public static String fmtDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long totalSec = d.toSeconds();
        if (totalSec < 60) return String.format("%.1fs", ms / 1000.0);
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        return minutes + "m " + seconds + "s";
    }
}
