// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.run.BuildPlanResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Console presentation for a simple-task command: spinner label plus success/failure tails from
 * {@link BuildPlanResult}. Optional {@code softFailure} forces a failure chip after a successful
 * plan (e.g. {@code jk run} with nothing executable). Duration suffix is appended by the framework.
 */
public record ConsoleSpec(
        String command,
        Function<BuildPlanResult, @Nullable String> onSuccess,
        Function<BuildPlanResult, @Nullable String> onFailure,
        boolean chip,
        boolean exec,
        @Nullable Function<BuildPlanResult, @Nullable String> softFailure) {

    /** Default generic success/failure finish ({@code chip}/{@code exec} false). */
    public ConsoleSpec(
            String command,
            Function<BuildPlanResult, @Nullable String> onSuccess,
            Function<BuildPlanResult, @Nullable String> onFailure) {
        this(command, onSuccess, onFailure, false, false);
    }

    public ConsoleSpec(
            String command,
            Function<BuildPlanResult, @Nullable String> onSuccess,
            Function<BuildPlanResult, @Nullable String> onFailure,
            boolean chip) {
        this(command, onSuccess, onFailure, chip, false);
    }

    public ConsoleSpec(
            String command,
            Function<BuildPlanResult, @Nullable String> onSuccess,
            Function<BuildPlanResult, @Nullable String> onFailure,
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
        return Theme.paint(body, Theme.active().darkGray().italic());
    }

    /**
     * A diagnostic error report: red phase pill + "Failure" and a railed body (coords / paths
     * painted). Prefer {@link #renderError(String, String, String)} when a code is available.
     */
    public static String errorLine(String step, String message) {
        return DiagnosticReport.renderError(step, null, message);
    }

    /** Render an error diagnostic for the console, per its {@code code}. */
    public static String renderError(BuildPlanResult.Diagnostic d) {
        return renderError(d, true);
    }

    /** Like {@link #renderError(BuildPlanResult.Diagnostic)} with an explicit header. */
    public static String renderError(BuildPlanResult.Diagnostic d, boolean showHeader) {
        return renderError(d.step(), d.code(), d.message(), d.module(), showHeader);
    }

    /** Render an error diagnostic from its parts (used by live + summary paths alike). */
    public static String renderError(String step, @Nullable String code, @Nullable String message) {
        return DiagnosticReport.renderError(step, code, message, null);
    }

    /** Like {@link #renderError(String, String, String)} with a {@code group:artifact} module. */
    public static String renderError(
            String step, @Nullable String code, @Nullable String message, @Nullable String module) {
        return DiagnosticReport.renderError(step, code, message, module);
    }

    /** Like {@link #renderError(String, String, String, String)} with an explicit header. */
    public static String renderError(
            String step, @Nullable String code, @Nullable String message, @Nullable String module, boolean showHeader) {
        return DiagnosticReport.renderError(step, code, message, module, showHeader);
    }

    /**
     * Identity of one diagnostic across its two wire forms: the live error line and the plan-finish
     * summary carry the same step, code and message, and nothing else they share is stable. The
     * summary form names no module, so the listener that holds both forms supplies the module it
     * is listening for: two modules failing the same way — a shared plugin error, a common missing
     * tool, an identical compiler diagnostic in copied code — are two diagnostics, and the second
     * module's must not be swallowed because the first module's live line recorded the same text.
     * A listener that rendered the live form records this key and skips the summary form, so one
     * error prints once on one surface.
     */
    public static String diagnosticKey(@Nullable String module, String step, String code, @Nullable String message) {
        return (module == null ? "" : module) + "\u0000" + step + "\u0000" + code + "\u0000"
                + (message == null ? "" : message);
    }

    /** {@code module}'s {@code errors} minus the ones whose {@link #diagnosticKey} is in {@code streamed}. */
    public static List<BuildPlanResult.Diagnostic> withoutStreamed(
            @Nullable String module, Iterable<BuildPlanResult.Diagnostic> errors, Set<String> streamed) {
        List<BuildPlanResult.Diagnostic> out = new ArrayList<>();
        for (BuildPlanResult.Diagnostic d : errors) {
            if (!streamed.contains(diagnosticKey(module, d.step(), d.code(), d.message()))) out.add(d);
        }
        return out;
    }

    /**
     * Append rendered errors, omitting the Compile/Kotlin/Groovy pill on later compiler
     * diagnostics that share the previous report's title and module.
     */
    public static void appendErrors(List<String> dest, Iterable<BuildPlanResult.Diagnostic> errors) {
        if (dest == null || errors == null) return;
        DiagnosticReport.CompilerHeaderRun headers = new DiagnosticReport.CompilerHeaderRun();
        for (BuildPlanResult.Diagnostic d : errors) {
            String rendered = renderError(d, headers.show(d.step(), d.code(), d.module()));
            if (rendered != null && !rendered.isEmpty()) dest.add(rendered);
        }
    }

    /** Render a warning diagnostic for the console, per its {@code code}. */
    public static String renderWarning(BuildPlanResult.Diagnostic d) {
        return renderWarning(d.step(), d.code(), d.message(), d.module());
    }

    /** Warning report: yellow phase pill + railed body. */
    public static String renderWarning(String step, String code, @Nullable String message) {
        return DiagnosticReport.renderWarning(step, code, message, null);
    }

    /** Like {@link #renderWarning(String, String, String)} with a {@code group:artifact} module. */
    public static String renderWarning(String step, String code, @Nullable String message, @Nullable String module) {
        return DiagnosticReport.renderWarning(step, code, message, module);
    }

    /**
     * A compiler warning under a yellow phase pill (legacy name kept for call sites).
     */
    public static String compilerWarning(String step, String message) {
        return DiagnosticReport.renderWarning(step, "javac", message);
    }

    /** Compiler diagnostics (javac/kotlinc) carry a verbatim multi-line block. */
    public static boolean isCompilerCode(@Nullable String code) {
        return "javac".equals(code) || "kotlinc".equals(code) || "groovyc".equals(code);
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
     * 1d 12h 13m 5s}. Spelled by {@link DurationText#human}.
     */
    public static String fmtDuration(Duration d) {
        return DurationText.human(d);
    }
}
