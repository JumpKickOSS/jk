// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.cli.tui.PlainAscii;
import cc.jumpkick.cli.tui.Rail;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.terminal.Style;
import java.util.Locale;

/**
 * Color/style provider for TUI, plan, and help renderers. Access via {@link #active()};
 * emission follows {@code --color} ({@link JkConfig.ColorChoice}): ALWAYS, NEVER (attributes kept),
 * or AUTO (respects {@code NO_COLOR} and TTY).
 */
public interface Theme {

    // --- style getters ----------------------------------------------------

    Style dim();

    /** Inactive/completed rail glyphs (┌ │ └) and completed step prompts — Jk Dark bright black. */
    Style darkGray();

    /**
     * 40% darker than {@link #darkGray()} (bright black) — the dimmest gray, for ultra-low-emphasis
     * filler.
     */
    Style darkBlack();

    /** The {@link #darkBlack()} color as an {@link Rgb} — for use as a background fill. */
    Rgb darkBlackColor();

    /**
     * Neutral mid-gray body text ({@code #A0A0A0}) — ordinary gray between black and white. Use for
     * default step/detail prose; not the dim "bright black" rail color ({@link #darkGray()}).
     */
    Style midGray();

    /** De-emphasised body text adjacent to bright labels — Jk Dark primary-light. */
    Style normalGray();

    /** Medium gray — the shared badge/chip background (and its powerline caps). */
    Style gray();

    /** The {@link #gray()} color as an {@link Rgb} — chip fill and the powerline cap that closes it. */
    Rgb grayColor();

    /** Active rail / step bullet — bright cyan highlight. */
    Style activeStep();

    Style completedStep();

    /** Bold + bright white; used for focused option labels and the input buffer. */
    Style focused();

    /** Plain foreground (not bold, not dim); used for settled answer text. */
    Style settled();

    /** Alias for {@link #settled} — plain white with no weight modifier. */
    Style plainWhite();

    /** Bright white with no weight modifier (unlike {@link #focused}, which is bold). */
    Style brightWhite();

    /**
     * Dark gray (same as the inactive rail glyphs). Used for the prompt line of a step once it has
     * been settled — the gray de-emphasises the question against the focused-white text of the active
     * step, while matching the rail's color above and below.
     */
    Style completedPrompt();

    /** Red; used for inline error messages and cancellation closers (distinct from the accent). */
    Style error();

    /** Bold + green; used for success-banner text. */
    Style success();

    /** Yellow; used to call attention to state like "default" / current selection. */
    Style warning();

    /** Blue — used elsewhere; no longer a gradient endpoint. */
    Style blue();

    /** Material indigo ({@code #3F51B5}) — the web {@code --indigo} token. */
    Style indigo();

    /** Brand primary — neon electric blue, the brand base color (Jk Dark primary, {@code #3D9BFF}). */
    Style primary();

    /** Cyan — used to label structural keys like scopes (Jk Dark cyan). */
    Style cyan();

    /**
     * Black — the darkest palette color; used for low-emphasis filler like dotted leaders (Jk Dark
     * normal black).
     */
    Style black();

    /** Bright green — used for the settled-answer arrow and "➜" prefixes. */
    Style brightGreen();

    /** Bright cyan — used for artifact short-names in coordinate output. */
    Style brightCyan();

    // --- coordinate-segment styles ---------------------------------------
    // The three roles of a printed group:artifact:version coordinate.

    /** Coordinate group segment — cyan (Jk Dark cyan {@code #00D4E0}). */
    Style coordGroup();

    /** Coordinate artifact/name segment — bold bright-cyan (Jk Dark bright cyan {@code #00F0FF}). */
    Style coordName();

    /** Coordinate version segment — midpoint between bright-cyan and white ({@code #82F7F8}). */
    Style coordVersion();

    /**
     * Chip / pill badge (jk tree scope sections, jk explain Fully Cached / Rebuild) — black on a
     * bright-black background.
     */
    Style scopeBadge();

    /** Header segment (jk explain's "Build Plan for …") — near-black on a cyan background. */
    Style cyanBadge();

    /** {@code jk explain} "Build Plan" header chip — white on dark royal blue ({@code #0F4786}). */
    Style planBadge();

    /** The dark royal blue behind {@link #planBadge()} — for the powerline cap. */
    Rgb planBadgeColor();

    /** Wizard title chip — white on the deep brand blue ({@code #124A8C}), for AA contrast. */
    Style indigoBadge();

    /** The deep blue behind {@link #indigoBadge()} — for the powerline cap. */
    Rgb indigoBadgeColor();

    /** The live build plan chip (spinner + command) — white on dark royal blue ({@code #0F4786}). */
    Style planChip();

    /** The settled success chip ({@code ✓ Build}) — white on green. */
    Style planSuccessChip();

    /** The settled failure chip ({@code ‼ Build}) — white on red. */
    Style planFailureChip();

    /** The green behind {@link #planSuccessChip()} — foreground of the powerline cap. */
    Rgb planChipColor();

    /** The failure chip's red — the foreground of the powerline cap that closes the red chip. */
    Rgb planFailColor();

    /**
     * Layer {@code bg} as the background of {@code base}, keeping its foreground and attributes
     * (unless {@code --color} disables color). Used to build the plan-header pill: the spinner and
     * name sit on the bar gradient's left-most color, and the U+E0B0 cap pairs that same color (as
     * foreground) with the bar's lead color (as background) so it tapers the pill into the bar.
     */
    Style withBackground(Style base, Rgb bg);

    /** Bright yellow — used for the {@code default} JDK status. */
    Style brightYellow();

    Style bright(int r, int g, int b);

    /** {@link Rgb} overload of {@link #bright(int, int, int)}. */
    Style bright(Rgb c);

    // --- help-semantic styles --------------------------------------------

    /** Section heading in help output — bold green. */
    Style sectionHeading();

    /** Command name in help output — bold cyan. */
    Style commandName();

    /** Parameter/option label in help output — cyan. */
    Style paramLabel();

    /** Inline highlight in help output — yellow. */
    Style highlight();

    /** Filesystem paths (relative or absolute) shown to the user — periwinkle. */
    Style path();

    /** Shell commands and command-lines shown to the user — neon orange ({@code #FF8329}). */
    Style shell();

    // --- syntax-highlight styles (compiler-diagnostic source snippets) ----
    // GitHub dark-theme palette, so highlighted snippets read like github.com.

    /** Language keyword (public, class, fun, return, …) — GitHub red {@code #ff7b72}. */
    Style synKeyword();

    /** Type / class name (Capitalized identifier) — GitHub orange {@code #ffa657}. */
    Style synType();

    /** Function / method name (identifier before {@code (}) — GitHub purple {@code #d2a8ff}. */
    Style synFunction();

    /** Named constant (ALL_CAPS identifier) — GitHub blue {@code #79c0ff}. */
    Style synConstant();

    /** String / char / text-block literal — GitHub light-blue {@code #a5d6ff}. */
    Style synString();

    /** Numeric literal — GitHub blue {@code #79c0ff}. */
    Style synNumber();

    /** Line/block comment — GitHub gray {@code #8b949e}. */
    Style synComment();

    /** Annotation use ({@code @Test}, {@code @Override}, …) — GitHub purple {@code #d2a8ff}. */
    Style synAnnotation();

    /**
     * Namespace / package qualifier (the {@code java.util} in {@code java.util.List}, the package
     * segments of a stack-frame class) — GitHub gray {@code #8b949e}, so the qualifier recedes behind
     * the simple type name.
     */
    Style synNamespace();

    /** Punctuation ({@code ; . , : ( ) [ ] { }}) — GitHub gray {@code #8b949e}. */
    Style synPunctuation();

    /** Error label/prefix — bold red. */
    Style errorLabel();

    /**
     * The {@code tip:} suggestion accent in error blocks, as a raw SGR parameter body (no {@code
     * ESC[} / {@code m}). This is a legacy 16-color accent (bright green) that does not round-trip
     * byte-identically through {@link Style#toAnsi()}, so it is sourced as a literal body
     * here — keeping the color choice in the theme layer, not in the renderer.
     */
    String tip();

    /**
     * The {@code --help} hint accent in error blocks, as a raw SGR parameter body (no {@code ESC[} /
     * {@code m}). A legacy 16-color accent (bold bright cyan); see {@link #tip()} for why it is a
     * literal body rather than an {@link Style}.
     */
    String helpHint();

    // --- gradients --------------------------------------------------------

    /** Gradient for {@code jk init}/wizard titles. */
    Gradient titleGradient();

    /** Gradient for the progress-bar fill. */
    Gradient progressGradient();

    /** Gradient for the spinner frames. */
    Gradient spinnerGradient();

    /** Gradient a failed progress bar repaints in. */
    Gradient failureGradient();

    /**
     * Per-codepoint truecolor lerp across {@link #titleGradient()}, bold stamped on every cell
     * ({@code 1;38;2;R;G;B}).
     */
    String gradientHeaderAnsi(String text);

    /** Maps (state, glyph) to the style used to render that rail glyph. */
    Style railStyle(Rail.StepState state, Rail.RailGlyph glyph);

    // --- static, theme-independent helpers --------------------------------

    /** Holds the active theme; defaults to a {@link JkDarkTheme} singleton. */
    final class Holder {
        private Holder() {}

        private static volatile Theme active = new JkDarkTheme();
    }

    /** The active theme. Defaults to {@link JkDarkTheme}. */
    static Theme active() {
        return Holder.active;
    }

    /** Replace the active theme. */
    static void setActive(Theme theme) {
        Holder.active = theme;
    }

    /**
     * True when ANSI escape sequences (color, cursor movement, Unicode glyphs) may be emitted.
     * False when output is piped or {@code --no-ansi} / No-ANSI mode is active. Implementations
     * default to {@link #colorEnabled()} — subclasses may override when they track TTY state
     * separately from color choice.
     */
    default boolean isAnsi() {
        return colorEnabled();
    }

    /** True when foreground color should be emitted, given the resolved {@code --color} choice. */
    static boolean colorEnabled() {
        // No-ANSI triggers (--no-ansi, TERM=dumb, CI=true/1) imply no color.
        if (cc.jumpkick.config.SessionContext.current().config().noAnsiOr(false)) return false;
        if ("dumb".equals(System.getenv("TERM"))) return false;
        String ci = System.getenv("CI");
        if ("true".equalsIgnoreCase(ci) || "1".equals(ci)) return false;
        var choice = cc.jumpkick.config.SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            // AUTO: emit color unless NO_COLOR is set. We don't gate on isatty —
            // many jk consumers (CI logs, `less -R`, pipes into other formatters)
            // benefit from preserved color, and users who want strictly plain
            // output can pass `--color never`.
            case AUTO -> {
                var nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }

    /**
     * Resolve a RichText color token to a style. Accepts theme role names ({@code success},
     * {@code path}, {@code mid-gray}, …) and CSS aliases ({@code yellow} → warning amber, {@code
     * red} → error). Underscores and case are ignored. Returns {@code null} for unknown names
     * (hex colors are not looked up here).
     */
    default Style styleNamedOrNull(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "dim" -> dim();
            case "dark-gray", "darkgray" -> darkGray();
            case "dark-black", "darkblack" -> darkBlack();
            case "mid-gray", "midgray" -> midGray();
            case "normal-gray", "normalgray" -> normalGray();
            case "gray" -> gray();
            case "focused" -> focused();
            case "settled" -> settled();
            case "white", "bright-white", "brightwhite" -> brightWhite();
            case "error", "red" -> error();
            case "success", "green" -> success();
            case "warning", "yellow" -> warning();
            case "blue" -> blue();
            case "indigo" -> indigo();
            case "primary", "plan" -> primary();
            case "cyan" -> cyan();
            case "black" -> black();
            case "bright-green", "brightgreen" -> brightGreen();
            case "bright-cyan", "brightcyan" -> brightCyan();
            case "bright-yellow", "brightyellow" -> brightYellow();
            case "path" -> path();
            case "shell" -> shell();
            case "highlight" -> highlight();
            case "coord-group", "coordgroup" -> coordGroup();
            case "coord-name", "coordname" -> coordName();
            case "coord-version", "coordversion" -> coordVersion();
            // Web --prog-b neon violet; Theme has no magenta() getter.
            case "magenta" -> bright(0xC0, 0x4D, 0xFF);
            default -> null;
        };
    }

    /** {@link #styleNamedOrNull} or {@link IllegalArgumentException} for an unknown token. */
    default Style styleNamed(String name) {
        Style style = styleNamedOrNull(name);
        if (style == null) {
            throw new IllegalArgumentException("unknown style name: " + name);
        }
        return style;
    }

    /**
     * Wrap {@code text} in attribute-leading SGR. Never rewrites glyphs. {@code --no-ansi} goes
     * through {@link PlainAscii}.
     */
    static String colorize(String text, Style style) {
        if (!Theme.active().isAnsi()) return PlainAscii.transform(text);
        return style.render(text);
    }
}
