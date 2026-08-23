// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Badge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.terminal.Style;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-facing failure / warning reports for plan diagnostics: a colored phase pill (same language
 * as explain/tree badges and the web phase strip) plus a heavy vertical rail, with coords and paths
 * painted in the shared theme roles.
 *
 * <p>Replaces the flat {@code ✘ Error [parse-build]: …} banner for non-test-failure diagnostics.
 * Per-test failures still use the dedicated {@link TestFailureHighlight} block from run-tests output.
 */
public final class DiagnosticReport {

    /** Heavy vertical rail (U+2503), shared with {@link TestFailureHighlight}. */
    public static final String RAIL = "┃";

    /** Heavy rail corner + bar that closes a report body (U+2517 U+2501). */
    public static final String FOOTER = "┗━";

    /**
     * Absolute or clearly path-like tokens in free-form messages. Stops at whitespace or common
     * closers ({@code )}, {@code ]}, {@code ,}).
     */
    private static final Pattern PATH_TOKEN = Pattern.compile("(?:"
            + "(?:[A-Za-z]:)?[/\\\\][^\\s\\]\\),]+" // absolute unix / windows
            + "|(?:\\./|\\.\\./)[^\\s\\]\\),]+" // relative with ./
            + "|target/[^\\s\\]\\),]+" // monorepo targets
            + ")");

    /** {@code group:artifact} or GAV (no whitespace). */
    private static final Pattern COORD_TOKEN =
            Pattern.compile("\\b([a-zA-Z_][\\w.-]*):([a-zA-Z_][\\w.-]*)(?::([\\w.-]+))?\\b");

    private DiagnosticReport() {}

    // --- public API (ConsoleSpec / listeners) --------------------------------

    /** Full multi-line error report for a plan diagnostic. Empty when suppressed (test-failure). */
    public static String renderError(String step, String code, String message) {
        return renderError(step, code, message, null);
    }

    /** Like {@link #renderError(String, String, String)} with a {@code group:artifact} module. */
    public static String renderError(String step, String code, String message, String module) {
        return renderError(step, code, message, module, true);
    }

    /**
     * {@code showHeader} false omits the phase pill — used for later compiler errors in the same
     * module so they stack under the first report (dashboard parity).
     */
    public static String renderError(String step, String code, String message, String module, boolean showHeader) {
        if ("test-failure".equals(code)) return "";
        if ("verbatim".equals(code)) return message == null ? "" : message;
        String title = titleFor(step, code);
        String body = ConsoleSpec.isCompilerCode(code)
                ? railBlock(CompilerDiagnostic.render(nullToEmpty(message), "error"), Role.ERROR)
                : railBlock(paintProse(nullToEmpty(message)), Role.ERROR);
        if (!showHeader) return body;
        return header(title, Role.ERROR, module) + "\n" + body;
    }

    /**
     * Collapse key for consecutive compiler reports that share a pill. {@code null} means the
     * report always carries its own header (non-compiler diagnostics).
     */
    public static String compilerHeaderKey(String step, String code, String module) {
        if (!ConsoleSpec.isCompilerCode(code)) return null;
        return titleFor(step, code) + "\0" + (module == null ? "" : module);
    }

    /** Tracks whether the next compiler diagnostic should repeat the phase pill. */
    public static final class CompilerHeaderRun {
        private String prev;

        public boolean show(String step, String code, String module) {
            String key = compilerHeaderKey(step, code, module);
            boolean show = key == null || !key.equals(prev);
            prev = key;
            return show;
        }
    }

    /** Warning report: yellow pill + rail (compiler warnings keep their body paint). */
    public static String renderWarning(String step, String code, String message) {
        return renderWarning(step, code, message, null);
    }

    /** Like {@link #renderWarning(String, String, String)} with a {@code group:artifact} module. */
    public static String renderWarning(String step, String code, String message, String module) {
        String title = titleFor(step, code);
        if (ConsoleSpec.isCompilerCode(code)) {
            return header(title, Role.WARNING, module)
                    + "\n"
                    + railBlock(CompilerDiagnostic.render(nullToEmpty(message), "warning"), Role.WARNING);
        }
        return header(title, Role.WARNING, module) + "\n" + railBlock(paintProse(nullToEmpty(message)), Role.WARNING);
    }

    /**
     * Human title for the pill: title-cased task name ({@code parse-build} → {@code Parse Build}).
     * Falls back to the BuildStage display name when the step is blank / composite.
     */
    public static String titleFor(String step, String code) {
        String key = stepKey(step);
        if (key.isEmpty() || "composite".equals(key) || "workspace".equals(key)) {
            if (code != null && !code.isBlank() && !"workspace".equals(code)) {
                return titleCaseWords(code.replace('-', ' ').replace('_', ' '));
            }
            BuildStage stage = BuildStage.ofTaskName(key.isEmpty() ? code : key);
            return stage == BuildStage.OTHER ? "Build" : stage.displayName();
        }
        // Prefer task humanization (Parse Build) over coarse stage (Resolve) — matches the wire
        // step the user saw failing, and still maps under the right stage in the live tree.
        return titleCaseWords(key.replace('-', ' ').replace('_', ' '));
    }

    /** Title-case each whitespace-separated word ({@code parse build} → {@code Parse Build}). */
    public static String titleCaseWords(String spaced) {
        if (spaced == null || spaced.isBlank()) return "";
        String[] parts = spaced.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            if (p.length() == 1) sb.append(Character.toUpperCase(p.charAt(0)));
            else
                sb.append(Character.toUpperCase(p.charAt(0)))
                        .append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // --- header / rail -------------------------------------------------------

    private enum Role {
        ERROR,
        WARNING
    }

    static String header(String title, Role role) {
        return header(title, role, null);
    }

    static String header(String title, Role role, String module) {
        Theme t = Theme.active();
        String word = role == Role.ERROR ? "Failure" : "Warning";
        String in = module == null || module.isBlank() ? "" : " in " + module;
        if (!t.isAnsi()) {
            return "[" + title + "] " + word + in;
        }
        // Fail chip: white on plan red. Warn chip: black on amber — same ink as Pill.Look.WARNING
        // / cancelled-job chips (white-on-amber washes out on most terminals).
        Rgb chipRgb = role == Role.ERROR ? t.planFailColor() : Rgb.hex(0xFFB800);
        Style ink = role == Role.ERROR ? t.bright(255, 255, 255) : t.bright(0, 0, 0);
        Style body = t.withBackground(ink, chipRgb);
        Style caps = t.bright(chipRgb);
        String pill = Badge.pill(title, GlobalConfig.nerdFont().pill(), body, caps);
        Style wordStyle = t.midGray();
        StringBuilder sb = new StringBuilder();
        sb.append(pill).append(' ').append(Theme.colorize(word, wordStyle));
        if (module != null && !module.isBlank()) {
            sb.append(Theme.colorize(" in ", t.midGray()));
            int colon = module.indexOf(':');
            if (colon > 0 && colon < module.length() - 1) {
                sb.append(Coords.ga(module.substring(0, colon), module.substring(colon + 1)));
            } else {
                sb.append(Theme.colorize(module, t.coordName()));
            }
        }
        return sb.toString();
    }

    private static String railBlock(String paintedBody, Role role) {
        StringBuilder sb = new StringBuilder();
        if (paintedBody != null && !paintedBody.isEmpty()) {
            String[] lines = paintedBody.split("\n", -1);
            // Drop a single trailing empty from split.
            int n = lines.length;
            if (n > 0 && lines[n - 1].isEmpty()) n--;
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append('\n');
                sb.append(railLine(lines[i], role));
            }
            if (n > 0) sb.append('\n');
        }
        sb.append(footerLine(role));
        return sb.toString();
    }

    private static String railLine(String paintedContent, Role role) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return " | " + (paintedContent == null ? "" : paintedContent);
        }
        Style railStyle = role == Role.ERROR ? t.error() : t.warning();
        return " " + Theme.colorize(RAIL, railStyle) + " " + (paintedContent == null ? "" : paintedContent);
    }

    /** Closing rail corner: {@code  ┗━} (red/yellow). Plain: {@code  +--}. */
    public static String footerLine(Role role) {
        Theme t = Theme.active();
        if (!t.isAnsi()) return " +--";
        Style railStyle = role == Role.ERROR ? t.error() : t.warning();
        return " " + Theme.colorize(FOOTER, railStyle);
    }

    /** Error-role footer for test-failure blocks and other callers. */
    public static String errorFooter() {
        return footerLine(Role.ERROR);
    }

    // --- message prose (coords + paths) --------------------------------------

    /**
     * Paint free-form diagnostic prose: Maven coords, filesystem paths (project-relative via
     * {@link PathDisplay}), mid-gray body text.
     */
    public static String paintProse(String message) {
        if (message == null || message.isEmpty()) return "";
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return relativizePathsInPlain(message);
        }
        // Walk the string; at each position try path, then coord, else copy mid-gray runs.
        StringBuilder out = new StringBuilder(message.length() + 64);
        int i = 0;
        int n = message.length();
        while (i < n) {
            Matcher path = PATH_TOKEN.matcher(message).region(i, n);
            if (path.lookingAt()) {
                String tok = path.group();
                // Trim trailing punctuation that PATH_TOKEN might include (rare).
                out.append(paintPathToken(tok, t));
                i = path.end();
                continue;
            }
            Matcher coord = COORD_TOKEN.matcher(message).region(i, n);
            if (coord.lookingAt()) {
                String g = coord.group(1);
                String a = coord.group(2);
                String v = coord.group(3);
                if (v != null) out.append(Coords.gav(g, a, v));
                else out.append(Coords.ga(g, a));
                i = coord.end();
                continue;
            }
            // Consume a plain run until the next path/coord start.
            int j = i + 1;
            while (j < n) {
                if (PATH_TOKEN.matcher(message).region(j, n).lookingAt()) break;
                if (COORD_TOKEN.matcher(message).region(j, n).lookingAt()) break;
                j++;
            }
            out.append(Theme.colorize(message.substring(i, j), t.midGray()));
            i = j;
        }
        return out.toString();
    }

    private static String paintPathToken(String tok, Theme t) {
        String display = relativizePathToken(tok);
        return Theme.colorize(display, t.path());
    }

    /** Relativize a path-like token; leave non-existent / non-path strings alone. */
    static String relativizePathToken(String tok) {
        if (tok == null || tok.isEmpty()) return tok;
        try {
            Path p = Path.of(tok);
            // Only relativize when it looks absolute or exists under the project.
            if (p.isAbsolute() || tok.startsWith("target/") || Files.exists(p)) {
                return PathDisplay.of(p);
            }
        } catch (RuntimeException ignored) {
            // keep raw
        }
        return tok;
    }

    private static String relativizePathsInPlain(String message) {
        Matcher m = PATH_TOKEN.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(relativizePathToken(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // --- helpers -------------------------------------------------------------

    private static String stepKey(String step) {
        if (step == null || step.isBlank()) return "";
        String s = step.trim();
        // "run-tests/test-failure" → task side
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        // qualified task ids "name@hash"
        int at = s.indexOf('@');
        if (at > 0) s = s.substring(0, at);
        return s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
