// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.jdk.JdkProgressLabel;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.NullMarked;

/** Token coloring and format helpers for JkManager tree/header rows. */
@NullMarked
public final class JkManagerColor {

    private JkManagerColor() {}

    static String detailForDisplay(String module, String message) {
        if (message == null || message.isBlank()) return "";
        String msg = message.trim();
        String mod = module == null ? "" : module.trim();
        if (!mod.isEmpty() && msg.startsWith(mod + " :: ")) {
            msg = msg.substring(mod.length() + 4).trim();
        }
        return msg;
    }

    /**
     * Brief under a failed tree row: dim rail/indent, red message only (not the whole line).
     *
     * @param last whether this is the last tree entry (closing branch → space indent)
     */
    static String renderBriefErrorLine(boolean last, String brief) {
        String errIndent = last ? "    " : " │  ";
        Theme t = Theme.active();
        return Theme.colorize(errIndent, t.darkGray()) + Theme.colorize(brief == null ? "" : brief, t.error());
    }

    /**
     * Color a live step detail under the phase label.
     *
     * <ul>
     * <li><b>{@code Class.method(…)} form only</b> (run-tests live labels): Java {@link
     * cc.jumpkick.cli.run.SyntaxHighlight} — not every step under the Test phase (compile-test
     * is phase Test too and must stay prose gray)
     * <li><b>Everything else</b>: prose in mid-gray ({@link Theme#midGray} {@code #A0A0A0}), never
     * cyan and never dim bright-black, with:
     * <ul>
     * <li>integers / counts / sizes → yellow ({@link Theme#warning})
     * <li>size units ({@code MiB}, {@code KB}, …) stay gray after the number
     * <li>artifact filenames and path-like tokens → {@link Theme#path}
     * <li>Maven {@code group:artifact(:version)} → {@link cc.jumpkick.cli.theme.Coords}
     * <li>fetched short-names / bare library ids after resolve verbs → coord short-name
     * <li>short cache key hex → dimmest gray
     * </ul>
     * <li>Trailing {@code [wN]} worker tags stay gray
     * </ul>
     */
    static String colorDetail(String phase, String detail, Theme t) {
        if (detail == null || detail.isBlank()) return "";
        String body = detail;
        String worker = "";
        // progressLabel appends " [w2]" — keep it outside the Java highlighter.
        int w = detail.lastIndexOf("  [w");
        if (w > 0 && detail.endsWith("]")) {
            body = detail.substring(0, w);
            worker = detail.substring(w);
        }
        // ensure-jdk: "downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%" — cyan name, blue/gray bar.
        String jdkPainted = colorJdkProgressDetail(body, t);
        if (jdkPainted != null) {
            return worker.isEmpty() ? jdkPainted : jdkPainted + Theme.colorize(worker, t.midGray());
        }
        // native-image: "{bin} · classpath input size: ~N MiB" — path color + bold white size.
        String nativePainted = colorNativeClasspathSizeDetail(body, t);
        if (nativePainted != null) {
            return worker.isEmpty() ? nativePainted : nativePainted + Theme.colorize(worker, t.midGray());
        }
        // Wire may carry FQCNs (java.nio.file.Path, pkg.FooTest); display simple names only.
        body = cc.jumpkick.cli.run.TestFailureHighlight.shortDisplayLabel(body);
        // Only syntax-highlight true member refs (FooTest.bar). Phase "Test" also hosts
        // compile-test labels like "compiling 12 sources" — those must stay mid-gray prose
        // (SyntaxHighlight paints unmatched text as terminal default/white).
        String painted = looksLikeJavaMember(body)
                ? cc.jumpkick.cli.run.SyntaxHighlight.highlight(body, -1)
                : colorProseDetail(body, t);
        if (worker.isEmpty()) return painted;
        return painted + Theme.colorize(worker, t.midGray());
    }

    /**
     * Paint {@code downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%} / {@code installing … 100%}: verb and
     * percent mid-gray, product name cyan, filled bar cells blue, empty cells dark gray.
     */
    static String colorJdkProgressDetail(String detail, Theme t) {
        JdkProgressLabel.Parsed p = JdkProgressLabel.tryParse(detail);
        if (p == null) return null;
        StringBuilder out = new StringBuilder(detail.length() + 64);
        out.append(Theme.colorize(p.verb(), t.midGray()));
        out.append(Theme.colorize(" ", t.midGray()));
        out.append(Theme.colorize(p.name(), t.cyan()));
        if (p.hasBar()) {
            out.append(Theme.colorize(" ", t.midGray()));
            for (int i = 0; i < p.bar().length(); i++) {
                char c = p.bar().charAt(i);
                out.append(Theme.colorize(String.valueOf(c), c == JdkProgressLabel.FILLED ? t.blue() : t.darkGray()));
            }
            out.append(Theme.colorize(" ", t.midGray()));
            out.append(Theme.colorize(p.percent() + "%", t.midGray()));
        }
        return out.toString();
    }

    /**
     * Paint {@code {filename} · classpath input size: ~N MiB}: filename in {@link Theme#path}
     * (periwinkle), size number bold bright-white, prose mid-gray. Returns null when the detail
     * is not this shape so the generic prose painter handles it.
     */
    static String colorNativeClasspathSizeDetail(String detail, Theme t) {
        if (detail == null) return null;
        final String marker = " · classpath input size: ~";
        int sep = detail.indexOf(marker);
        if (sep <= 0) return null;
        String name = detail.substring(0, sep);
        String after = detail.substring(sep + marker.length()); // "1.4 MiB" or "12 MiB"
        int sp = after.indexOf(' ');
        if (sp <= 0) return null;
        String num = after.substring(0, sp);
        String unitAndRest = after.substring(sp); // " MiB" (+ anything after)
        if (!Character.isDigit(num.charAt(0))) return null;
        // focused() = bold + bright white (same as focused option labels / input buffer).
        return Theme.colorize(name, t.path())
                + Theme.colorize(marker.substring(0, marker.length() - 1), t.midGray()) // " · classpath input size: "
                + Theme.colorize("~" + num, t.focused())
                + Theme.colorize(unitAndRest, t.midGray());
    }

    /**
     * Free-text step labels: mid-gray ({@code #A0A0A0}) prose with numbers, paths, coordinates, and
     * fetch names picked out. Never uses cyan for body text (reserved for {@code group:artifact} on
     * the module segment) and never uses dim bright-black ({@link Theme#darkGray}) for default
     * prose.
     */
    static String colorProseDetail(String text, Theme t) {
        if (text == null || text.isEmpty()) return "";
        AttributedStyle gray = t.midGray(); // #A0A0A0 — ordinary gray, not dim chrome
        AttributedStyle number = t.warning(); // yellow counts (e.g. "Compiling N sources")
        AttributedStyle path = t.path();
        AttributedStyle hash = t.darkGray(); // slightly dimmer than body — cache key hex
        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0;
        int n = text.length();
        // Track the previous word (lowercase) so "fetched foo" / "resolve bar" can tint the name.
        String prevWord = "";
        while (i < n) {
            char c = text.charAt(i);
            // Skip whitespace as gray, then continue.
            if (Character.isWhitespace(c)) {
                int j = i + 1;
                while (j < n && Character.isWhitespace(text.charAt(j))) j++;
                out.append(Theme.colorize(text.substring(i, j), gray));
                i = j;
                continue;
            }

            // Lone punctuation (parens, arrows, …) so "(12 classes)" still blues the 12.
            if (!Character.isLetterOrDigit(c)
                    && c != '_'
                    && c != '-'
                    && c != '.'
                    && c != '/'
                    && c != '\\'
                    && c != '~'
                    && c != ':') {
                out.append(Theme.colorize(String.valueOf(c), gray));
                // Don't reset prevWord on '(' so "fetched (jackson-core)" still works.
                if (c != '(' && c != '[') prevWord = "";
                i++;
                continue;
            }

            // Pull the next non-whitespace token (may include: /. for coords & paths).
            int j = scanTokenEnd(text, i);
            int end = j;
            while (end > i && isTrailingPunct(text.charAt(end - 1))) end--;
            String tok = text.substring(i, end);
            String trail = text.substring(end, j); // trailing,); etc.

            // 1. Maven coordinate — group:artifact or GAV.
            if (looksLikeCoord(tok)) {
                out.append(colorCoord(tok));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = "";
                i = j;
                continue;
            }

            // 2. Path / artifact file.
            if (looksLikePathOrArtifact(tok)) {
                out.append(Theme.colorize(tok, path));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = "";
                i = j;
                continue;
            }

            // 3. Number (count or size). Hex cache keys prefer dim gray.
            if (Character.isDigit(c)) {
                int hexEnd = i;
                while (hexEnd < n && isHex(text.charAt(hexEnd))) hexEnd++;
                if (hexEnd - i >= 8 && (hexEnd >= n || !Character.isLetterOrDigit(text.charAt(hexEnd)))) {
                    out.append(Theme.colorize(text.substring(i, hexEnd), hash));
                    prevWord = "";
                    i = hexEnd;
                    continue;
                }
                int k = i;
                while (k < n && Character.isDigit(text.charAt(k))) k++;
                if (k < n && text.charAt(k) == '.' && k + 1 < n && Character.isDigit(text.charAt(k + 1))) {
                    k++;
                    while (k < n && Character.isDigit(text.charAt(k))) k++;
                }
                out.append(Theme.colorize(text.substring(i, k), number));
                // Optional size unit immediately after (or after one space): MiB, KB, …
                int u = k;
                if (u < n && text.charAt(u) == ' ') {
                    int uEnd = scanTokenEnd(text, u + 1);
                    String unit = text.substring(u + 1, uEnd);
                    if (looksLikeSizeUnit(unit)) {
                        out.append(Theme.colorize(" ", gray));
                        out.append(Theme.colorize(unit, gray));
                        i = uEnd;
                        prevWord = "";
                        continue;
                    }
                } else if (u < n && Character.isLetter(text.charAt(u))) {
                    int uEnd = scanTokenEnd(text, u);
                    String unit = text.substring(u, uEnd);
                    if (looksLikeSizeUnit(unit)) {
                        out.append(Theme.colorize(unit, gray));
                        i = uEnd;
                        prevWord = "";
                        continue;
                    }
                }
                prevWord = "";
                i = k;
                continue;
            }

            // 4. After resolve/fetch verbs, tint bare library / package short-names.
            if (isFetchOrResolveVerb(prevWord) && looksLikeLibraryShortName(tok)) {
                out.append(cc.jumpkick.cli.theme.Coords.shortName(tok));
                if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
                prevWord = tok.toLowerCase(Locale.ROOT);
                i = j;
                continue;
            }

            // 5. Plain gray word (and remember it for verb context).
            out.append(Theme.colorize(tok, gray));
            if (!trail.isEmpty()) out.append(Theme.colorize(trail, gray));
            prevWord = tok.toLowerCase(Locale.ROOT);
            i = j;
        }
        return out.toString();
    }

    /** End index of the token starting at {@code i} (exclusive). Stops at whitespace. */
    static int scanTokenEnd(String text, int i) {
        int n = text.length();
        int j = i;
        while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
        return j;
    }

    static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static boolean isTrailingPunct(char c) {
        return c == ',' || c == ')' || c == ';' || c == '(' || c == '[' || c == ']';
    }

    /** {@code MiB}, {@code KB}, {@code ms}, … — stay gray after a blue number. */
    static boolean looksLikeSizeUnit(String unit) {
        if (unit == null || unit.isEmpty()) return false;
        return switch (unit) {
            case "B",
                    "K",
                    "M",
                    "G",
                    "T",
                    "KB",
                    "MB",
                    "GB",
                    "TB",
                    "KiB",
                    "MiB",
                    "GiB",
                    "TiB",
                    "kb",
                    "mb",
                    "gb",
                    "kib",
                    "mib",
                    "gib",
                    "ms",
                    "s",
                    "m",
                    "h",
                    "files",
                    "file",
                    "sources",
                    "source",
                    "tests",
                    "test",
                    "jars",
                    "jar",
                    "classes",
                    "inputs",
                    "input" -> true;
            default -> false;
        };
    }

    /** Verbs whose following token is often a library / package id. */
    static boolean isFetchOrResolveVerb(String word) {
        if (word == null || word.isEmpty()) return false;
        return switch (word) {
            case "fetched",
                    "fetch",
                    "fetching",
                    "resolve",
                    "resolving",
                    "resolved",
                    "download",
                    "downloading",
                    "downloaded",
                    "install",
                    "installing",
                    "installed",
                    "load",
                    "loading",
                    "loaded",
                    "pushing",
                    "pushed",
                    "pulling",
                    "pulled" -> true;
            default -> false;
        };
    }

    /**
     * Bare library short-name ({@code jackson-core}, {@code junit}) — not prose, not a path, not a
     * pure number.
     */
    static boolean looksLikeLibraryShortName(String tok) {
        if (tok == null || tok.length() < 2) return false;
        if (looksLikePathOrArtifact(tok) || looksLikeCoord(tok)) return false;
        // Must start with a letter; allow letters, digits, dots, hyphens, underscores.
        char c0 = tok.charAt(0);
        if (!Character.isLetter(c0)) return false;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) return false;
        }
        // Reject common English words that follow "resolve" in prose.
        return switch (tok.toLowerCase(Locale.ROOT)) {
            case "deps",
                    "dependencies",
                    "classpath",
                    "jdk",
                    "java",
                    "sources",
                    "tests",
                    "resources",
                    "plugins",
                    "plugin",
                    "modules",
                    "module",
                    "lock",
                    "cache",
                    "the",
                    "a",
                    "an",
                    "to",
                    "for",
                    "from",
                    "with",
                    "and",
                    "or",
                    "of",
                    "in",
                    "on",
                    "via",
                    "no",
                    "up",
                    "date",
                    "hit",
                    "miss" -> false;
            default -> true;
        };
    }

    /**
     * Maven-style {@code group:artifact} or {@code group:artifact:version} (optionally with
     * classifier/extension segments). Requires at least one {@code ':'} and no whitespace.
     */
    static boolean looksLikeCoord(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        int first = tok.indexOf(':');
        if (first <= 0 || first == tok.length() - 1) return false;
        if (tok.indexOf('/') >= 0 || tok.indexOf('\\') >= 0) return false; // paths win
        String[] parts = tok.split(":", -1);
        if (parts.length < 2 || parts.length > 5) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) return false;
            }
        }
        // group usually has a dot (reverse-DNS) OR artifact has a hyphen/common form.
        return parts[0].indexOf('.') >= 0 || parts[1].indexOf('-') >= 0 || parts[1].length() >= 2;
    }

    /** Paint {@code g:a} / {@code g:a:v} with the same colors as dependency trees. */
    static String colorCoord(String tok) {
        String[] parts = tok.split(":", -1);
        if (parts.length == 2) return cc.jumpkick.cli.theme.Coords.ga(parts[0], parts[1]);
        if (parts.length >= 3) {
            // group:artifact:version — extra segments (classifier) stay on the version color.
            StringBuilder ver = new StringBuilder(parts[2]);
            for (int i = 3; i < parts.length; i++) ver.append(':').append(parts[i]);
            return cc.jumpkick.cli.theme.Coords.gav(parts[0], parts[1], ver.toString());
        }
        return Theme.colorize(tok, Theme.active().midGray());
    }

    /** {@code lib.jar}, {@code app.aar}, absolute/relative paths — not ordinary prose words. */
    static boolean looksLikePathOrArtifact(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        if (tok.indexOf('/') >= 0 || tok.indexOf('\\') >= 0) return true;
        if (tok.startsWith("~")) return true;
        int dot = tok.lastIndexOf('.');
        if (dot <= 0 || dot == tok.length() - 1) return false;
        String ext = tok.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jar",
                    "aar",
                    "apk",
                    "aab",
                    "war",
                    "ear",
                    "zip",
                    "tar",
                    "gz",
                    "tgz",
                    "properties",
                    "toml",
                    "xml",
                    "json",
                    "so",
                    "dylib",
                    "dll",
                    "exe",
                    "class",
                    "java",
                    "kt",
                    "kts",
                    "groovy" -> true;
            default -> false;
        };
    }

    /**
     * Capitalized type, optional {@code .method(…)}, no spaces (worker tags already stripped).
     * Compiled once: this runs per visible row on every 80 ms animator frame — with a 128 MB
     * heap, per-frame {@code String.matches} (a fresh {@code Pattern.compile}) is real garbage.
     */
    static final Pattern JAVA_MEMBER = Pattern.compile("[A-Z][\\w$]*(?:\\.[A-Za-z_][\\w$]*(?:\\([^)]*\\))?)?");

    /** {@code FooTest}, {@code FooTest.bar()}, or {@code FooTest.bar(Path)} — not free text. */
    static boolean looksLikeJavaMember(String s) {
        if (s == null || s.isEmpty()) return false;
        return JAVA_MEMBER.matcher(s).matches();
    }

    static String fmtClock(long millis) {
        return fmtClockSeconds(Math.max(0L, millis) / 1000L);
    }

    /**
     * Same as {@link #fmtClock} but from a whole-second counter — used so dual-clock faces share
     * one tick boundary.
     */
    static String fmtClockSeconds(long totalSec) {
        long s = Math.max(0L, totalSec);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m " + String.format("%02d", sec) + "s";
        if (m > 0) return m + "m " + String.format("%02d", sec) + "s";
        return sec + "s";
    }

    static String phaseLabel(String wire) {
        if (wire == null || wire.isEmpty()) return "?";
        return Character.toUpperCase(wire.charAt(0)) + wire.substring(1);
    }

    /**
     * {@code group:artifact} → cyan group + bold bright-cyan artifact (plan tree / failure
     * tails). Plain settled style if no colon.
     */
    public static String coloredModule(String module) {
        int colon = module.indexOf(':');
        if (colon < 0) return Theme.colorize(module, Theme.active().settled());
        Theme t = Theme.active();
        return Theme.colorize(module.substring(0, colon), t.coordGroup())
                + ":"
                + Theme.colorize(module.substring(colon + 1), t.coordName());
    }

    static String humanize(String stepKey) {
        if (stepKey == null || stepKey.isEmpty()) return "";
        String spaced = stepKey.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** "(1m 52s)" content: minutes+seconds past a minute, else just seconds. */
    static String fmtElapsed(long millis) {
        long totalSec = Math.max(0, millis) / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /**
     * Columns safe to paint on a single row of a {@code terminalCols}-wide terminal. Leaves the last
     * column free so a full-width write does not trip DEC auto-wrap (which can park the trailing
     * {@code …} on the next row where EL / the next tree line erase it).
     */
    static int rowColumnBudget(int terminalCols) {
        if (terminalCols <= 1) return Math.max(1, terminalCols);
        return terminalCols - 1;
    }

    /**
     * Hard-truncate an ANSI-colored string to {@code maxCols} visible columns (never wraps). When
     * cut, ends with {@code …} so long test member names stay on one line. Copies escape sequences
     * without counting them and appends a reset if the text was cut.
     *
     * <p>Callers painting to a live TTY should pass {@link #rowColumnBudget(int)} of the terminal
     * width, not the raw column count — see that method.
     *
     * <p>Width metric: {@code WCWidth} per code point (CJK = 2 columns), matching
     * {@link RenderContext#visibleWidth} and the resize reflow estimator — the two metrics
     * disagreeing inside one paint path let a CJK-heavy row exceed its painted budget, wrap, and
     * desync the cursor bookkeeping. WCWidth's tables are already linked into the
     * native image via {@code AttributedString.columnLength}; the ANSI scanning stays hand-rolled
     * ({@code AttributedString.fromAnsi} would add its parser, measured at +187–312 KB).
     * Surrogate pairs are consumed whole, so a cut can never emit a lone high surrogate.
     */
    static String truncateVisible(String s, int maxCols) {
        if (maxCols <= 0) return "";
        // No maxCols==1 shortcut: the reserve logic below already handles it — a 1-column
        // string fits as-is, only longer input degrades to the bare ellipsis.
        int budget = maxCols;
        StringBuilder sb = new StringBuilder(s.length());
        int visible = 0;
        boolean truncated = false;
        boolean linkOpen = false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033') { // copy the whole escape (CSI or OSC) verbatim — zero columns
                int j = RenderContext.skipEscape(s, i);
                boolean unterminatedOsc = i + 1 < s.length()
                        && s.charAt(i + 1) == ']'
                        && !(j - 1 > i
                                && (s.charAt(j - 1) == '\u0007'
                                        || (j - 2 > i && s.charAt(j - 2) == '\u001b' && s.charAt(j - 1) == '\\')));
                // A live unterminated OSC must never reach the terminal — it would swallow the
                // following output up to the next BEL. Non-SGR CSI (cursor motion ESC[1A, erase
                // ESC[2K, …) from raw tool output would move the real cursor mid-region-paint
                // and desync row bookkeeping — drop it too; only SGR coloring passes (JK-2109).
                boolean motionCsi = i + 1 < s.length() && s.charAt(i + 1) == '[' && j > i + 1 && s.charAt(j - 1) != 'm';
                if (!unterminatedOsc && !motionCsi) {
                    sb.append(s, i, j);
                    // Track OSC-8 hyperlink state: a cut inside the linked label drops the close
                    // that follows it, and SGR RESET does not end a hyperlink — the ellipsis, EL,
                    // and later rows would all become part of the link.
                    int state = osc8LinkState(s, i, j);
                    if (state != 0) linkOpen = state > 0;
                }
                i = j;
            } else {
                // One code point per step (never splitting a surrogate pair), wcwidth columns.
                int cp = s.codePointAt(i);
                int cpLen = Character.charCount(cp);
                int w = org.jline.utils.WCWidth.wcwidth(cp);
                if (w < 0) {
                    // C0/C1 controls (stray tab/backspace/CR in a step message — @DisplayName
                    // content flows in unsanitized). Emitting one at weight 0 advances real
                    // columns past the charged budget, wraps the row, and desyncs the cursor
                    // bookkeeping — drop it.
                    i += cpLen;
                    continue;
                }
                // Reserve one column for … only when the tail still costs columns.
                // need=1 ⇒ stop while the ellipsis still fits in budget.
                boolean moreAfter = hasVisibleFrom(s, i + cpLen);
                int need = moreAfter ? 1 : 0;
                if (visible + w + need > budget) {
                    truncated = true;
                    break;
                }
                sb.appendCodePoint(cp);
                visible += w;
                i += cpLen;
            }
        }
        if (truncated) {
            if (linkOpen) sb.append("\u001b]8;;\u0007"); // synthetic close before the ellipsis
            sb.append(JkManager.ELLIPSIS);
            sb.append(Ansi.RESET);
        }
        return sb.toString();
    }

    /**
     * Classifies a complete escape at {@code s[i..j)}: {@code 1} when it opens an OSC-8 hyperlink
     * (non-empty URI), {@code -1} when it closes one (empty URI), {@code 0} for anything else.
     */
    private static int osc8LinkState(String s, int i, int j) {
        if (j - i < 5 || s.charAt(i + 1) != ']' || s.charAt(i + 2) != '8' || s.charAt(i + 3) != ';') {
            return 0;
        }
        int end = j;
        if (s.charAt(end - 1) == '\u0007') {
            end--;
        } else if (end - 2 >= i && s.charAt(end - 2) == '\u001b' && s.charAt(end - 1) == '\\') {
            end -= 2;
        }
        int semi = s.indexOf(';', i + 4); // end of the params section
        if (semi < 0 || semi >= end) return 0; // malformed OSC-8 — no URI section
        return semi + 1 < end ? 1 : -1;
    }

    /**
     * True when {@code s[from..]} still spends at least one terminal column — skips ANSI escapes
     * (CSI or OSC) and zero-/negative-width code points (combining marks, VS16, ZWJ, controls).
     * Feeds the ellipsis reserve: a tail that costs nothing (cafe + combining acute at budget 4,
     * emoji + VS16 at its exact width) must render fully, not lose its last glyph to a {@code …}.
     */
    static boolean hasVisibleFrom(String s, int from) {
        for (int i = from; i < s.length(); ) {
            if (s.charAt(i) == '\033') {
                i = RenderContext.skipEscape(s, i);
                continue;
            }
            int cp = s.codePointAt(i);
            if (org.jline.utils.WCWidth.wcwidth(cp) > 0) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Restores {@code System.out}/{@code System.err} when closed (no checked exception). */
}
