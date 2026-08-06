// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.tui.Rail;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * The "Jk Dark" color scheme — a neon dark palette synced from the jk web dashboard, and the default {@link Theme}
 * implementation. The palette constants map jk's semantic roles (error, success, active accent, …)
 * onto named colors; the instance methods turn those roles into {@link AttributedStyle}s. jk emits
 * foreground colors only, so {@link #BACKGROUND}, {@link #CURSOR}, and the selection colors are
 * carried for completeness but not applied by current output.
 *
 * <p>Truecolor RGB everywhere; terminals without 24-bit color degrade to the nearest indexed color
 * via JLine's renderer.
 */
public final class JkDarkTheme implements Theme {

    public static final String NAME = "Jk Dark";
    public static final String VARIANT = "dark";

    public static final Rgb PRIMARY = Rgb.hex(0x3D9BFF); // web --run (neon electric blue) — matches NORMAL_BLUE
    public static final Rgb PRIMARY_DARK = Rgb.hex(0x124A8C); // web --runc (deep neon blue)
    public static final Rgb PRIMARY_LIGHT = Rgb.hex(0xC5CAE9); // light periwinkle (normalGray; no web token)
    public static final Rgb ACCENT = Rgb.hex(0xC04DFF); // web --prog-b (neon violet) — the gradient terminator

    public static final Rgb BACKGROUND = Rgb.hex(0x000000);
    public static final Rgb FOREGROUND = Rgb.hex(0xCFD8DC);

    public static final Rgb CURSOR = Rgb.hex(0xECEFF1);
    public static final Rgb CURSOR_TEXT = Rgb.hex(0x000000);

    public static final Rgb SELECTION_BG = Rgb.hex(0x124A8C); // web --runc (deep neon blue)
    public static final Rgb SELECTION_TEXT = Rgb.hex(0xFFFFFF);

    // Normal (the 8 base ANSI colors).
    public static final Rgb NORMAL_BLACK = Rgb.hex(0x263238); // Blue Grey 900
    public static final Rgb NORMAL_RED = Rgb.hex(0xFF3366); // web --err (neon red)
    public static final Rgb NORMAL_GREEN = Rgb.hex(0x00B368); // web --okc (deep neon green)
    public static final Rgb PIPELINE_GREEN = NORMAL_GREEN.darker(0.30); // deep green × 0.7 — build wedge background
    public static final Rgb NORMAL_YELLOW = Rgb.hex(0xFFB800); // web --warn (neon amber)
    public static final Rgb NORMAL_BLUE = Rgb.hex(0x3D9BFF); // web --run (neon electric blue)
    public static final Rgb NORMAL_MAGENTA = Rgb.hex(0xC04DFF); // web --prog-b (neon violet)
    public static final Rgb NORMAL_CYAN = Rgb.hex(0x00D4E0); // web --cg (neon cyan, secondary)
    public static final Rgb HEADER_BLUE = Rgb.hex(0x0F4786); // dark royal blue (#1565C0 × 0.7)
    public static final Rgb NORMAL_WHITE = Rgb.hex(0xCFD8DC); // Blue Grey 100
    public static final Rgb GRAY = Rgb.hex(0x90A4AE); // Blue Grey 300 — badge chips
    /** Neutral mid-gray for ordinary prose (step details) — between black and white, not dim chrome. */
    public static final Rgb MID_GRAY = Rgb.hex(0xA0A0A0);

    /** Filesystem paths shown to the user. */
    public static final Rgb PATH = Rgb.hex(0x969DD4); // periwinkle

    // Syntax-highlight palette — GitHub's dark default theme, so compiler
    // snippets read like a github.com code block rather than jk's own hues.
    public static final Rgb GH_KEYWORD = Rgb.hex(0xFF7B72); // red
    public static final Rgb GH_TYPE = Rgb.hex(0xFFA657); // orange
    public static final Rgb GH_FUNCTION = Rgb.hex(0xD2A8FF); // purple
    public static final Rgb GH_CONSTANT = Rgb.hex(0x79C0FF); // blue (also numbers)
    public static final Rgb GH_STRING = Rgb.hex(0xA5D6FF); // light blue
    public static final Rgb GH_COMMENT = Rgb.hex(0x8B949E); // gray

    // Bright (the 8 bright ANSI colors).
    public static final Rgb BRIGHT_BLACK = Rgb.hex(0x546E7A); // Blue Grey 600
    public static final Rgb DARK_BLACK = BRIGHT_BLACK.darker(0.40); // bright black × 0.6 — the dimmest gray
    public static final Rgb BRIGHT_RED = NORMAL_RED.brighter(0.25); // web --err lifted (web has no separate bright red)
    public static final Rgb BRIGHT_GREEN = Rgb.hex(0x00FF87); // web --ok (neon green)
    public static final Rgb BRIGHT_YELLOW =
            NORMAL_YELLOW.brighter(0.20); // web --warn lifted (web has no separate bright amber)
    public static final Rgb BRIGHT_BLUE = Rgb.hex(0x5AB0FF); // web --run-line (brighter running blue)
    public static final Rgb BRIGHT_MAGENTA =
            NORMAL_MAGENTA.brighter(0.15); // web --prog-b lifted (web has no separate bright violet)
    public static final Rgb BRIGHT_CYAN = Rgb.hex(0x00F0FF); // web --cn (neon cyan, accent)
    public static final Rgb BRIGHT_WHITE = Rgb.hex(0xECEFF1); // Blue Grey 50
    /** {@code coordVersion()} — 75% between {@link #BRIGHT_CYAN} and {@link #BRIGHT_WHITE} (50% brighter than the midpoint). */
    public static final Rgb COORD_VERSION = Rgb.hex(0xC1FBFC);

    // --- gradients --------------------------------------------------------
    // Named gradients, each independently tunable — all now read electric-blue → neon-violet, the
    // web dashboard's single blue→violet gradient (--prog-a → --prog-b). ACCENT and BRIGHT_MAGENTA
    // both resolve to the web violet, so the endpoints track the palette with no special-casing.
    /** Gradient for {@code jk init}/wizard titles — bright blue → violet. */
    private static final Gradient TITLE_GRADIENT = new Gradient(BRIGHT_BLUE, ACCENT);

    /**
     * Gradient for the progress-bar fill — blue → violet, so the fill starts electric blue and warms
     * toward neon violet; the empty track reads bright violet.
     */
    private static final Gradient PROGRESS_GRADIENT = new Gradient(PRIMARY, BRIGHT_MAGENTA);

    /** Gradient for the spinner frames — blue → violet. */
    private static final Gradient SPINNER_GRADIENT = new Gradient(PRIMARY, ACCENT);

    private static final Rgb FAILURE_GRADIENT_START = NORMAL_RED.darker(0.55);
    private static final Rgb FAILURE_GRADIENT_END = NORMAL_RED;

    /** Gradient a failed progress bar repaints in: deep red → neon red (both from the web --err red). */
    private static final Gradient FAILURE_GRADIENT = new Gradient(FAILURE_GRADIENT_START, FAILURE_GRADIENT_END);

    /** Apply a foreground color unless the resolved {@code --color} choice disables it. */
    private static AttributedStyle withColor(AttributedStyle base, int r, int g, int b) {
        return Theme.colorEnabled() ? base.foreground(r, g, b) : base;
    }

    /** {@link Rgb} overload of {@link #withColor(AttributedStyle, int, int, int)}. */
    private static AttributedStyle withColor(AttributedStyle base, Rgb c) {
        return withColor(base, c.r(), c.g(), c.b());
    }

    /** Apply a background color unless {@code --color} disables it. */
    private static AttributedStyle withBg(AttributedStyle base, Rgb c) {
        return Theme.colorEnabled() ? base.background(c.r(), c.g(), c.b()) : base;
    }

    @Override
    public boolean isAnsi() {
        // No-ANSI mode: explicitly set, dumb terminal, or CI=true/1.
        // NOT gated on colorEnabled() — NO_COLOR / --color never only disables color;
        // it does not disable Unicode glyphs, animations, or other ANSI sequences.
        if (cc.jumpkick.config.SessionContext.current().config().noAnsiOr(false)) return false;
        if ("dumb".equals(System.getenv("TERM"))) return false;
        String ci = System.getenv("CI");
        if ("true".equalsIgnoreCase(ci) || "1".equals(ci)) return false;
        return true;
    }

    @Override
    public AttributedStyle dim() {
        return AttributedStyle.DEFAULT.faint();
    }

    @Override
    public AttributedStyle darkGray() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_BLACK);
    }

    @Override
    public AttributedStyle darkBlack() {
        return withColor(AttributedStyle.DEFAULT, DARK_BLACK);
    }

    @Override
    public Rgb darkBlackColor() {
        return DARK_BLACK;
    }

    @Override
    public AttributedStyle midGray() {
        return withColor(AttributedStyle.DEFAULT, MID_GRAY);
    }

    @Override
    public AttributedStyle normalGray() {
        return withColor(AttributedStyle.DEFAULT, PRIMARY_LIGHT);
    }

    @Override
    public AttributedStyle gray() {
        return withColor(AttributedStyle.DEFAULT, GRAY);
    }

    @Override
    public AttributedStyle activeStep() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_CYAN);
    }

    @Override
    public AttributedStyle completedStep() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_GREEN);
    }

    @Override
    public AttributedStyle focused() {
        return withColor(AttributedStyle.DEFAULT.bold(), BRIGHT_WHITE);
    }

    @Override
    public AttributedStyle settled() {
        return withColor(AttributedStyle.DEFAULT, FOREGROUND);
    }

    @Override
    public AttributedStyle plainWhite() {
        return settled();
    }

    @Override
    public AttributedStyle brightWhite() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_WHITE);
    }

    @Override
    public AttributedStyle completedPrompt() {
        return gray();
    }

    @Override
    public AttributedStyle error() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_RED);
    }

    @Override
    public AttributedStyle success() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_GREEN);
    }

    @Override
    public AttributedStyle warning() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_YELLOW);
    }

    @Override
    public AttributedStyle blue() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_BLUE);
    }

    @Override
    public AttributedStyle primary() {
        return withColor(AttributedStyle.DEFAULT, PRIMARY);
    }

    @Override
    public AttributedStyle cyan() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_CYAN);
    }

    @Override
    public AttributedStyle black() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_BLACK);
    }

    @Override
    public AttributedStyle brightGreen() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_GREEN);
    }

    @Override
    public AttributedStyle brightCyan() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_CYAN);
    }

    @Override
    public AttributedStyle coordGroup() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_CYAN); // cyan
    }

    @Override
    public AttributedStyle coordName() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_CYAN); // bright-cyan
    }

    @Override
    public AttributedStyle coordVersion() {
        return withColor(AttributedStyle.DEFAULT, COORD_VERSION);
    }

    @Override
    public AttributedStyle scopeBadge() {
        // Pure-black text on gray — the shared badge/chip across the TUIs.
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_TEXT), GRAY);
    }

    @Override
    public AttributedStyle cyanBadge() {
        // Pure-black text on the cyan chip (the ▶ arrow is painted in NORMAL_CYAN — the
        // chip background — by the caller).
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_TEXT), NORMAL_CYAN);
    }

    @Override
    public AttributedStyle planBadge() {
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_FG), HEADER_BLUE);
    }

    @Override
    public Rgb planBadgeColor() {
        return HEADER_BLUE;
    }

    @Override
    public AttributedStyle indigoBadge() {
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_FG), PRIMARY_DARK);
    }

    @Override
    public Rgb indigoBadgeColor() {
        return PRIMARY_DARK;
    }

    /** Pure black (#000000) — the text color for every chip/badge that sets a background. */
    private static final Rgb CHIP_TEXT = Rgb.hex(0x000000);

    /** Pure white (#FFFFFF) — the foreground color for dark-background badge chips. */
    private static final Rgb CHIP_FG = Rgb.hex(0xFFFFFF);

    @Override
    public AttributedStyle planChip() {
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_FG), HEADER_BLUE);
    }

    @Override
    public AttributedStyle planSuccessChip() {
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_FG), PIPELINE_GREEN);
    }

    @Override
    public AttributedStyle planFailureChip() {
        return withBg(withColor(AttributedStyle.DEFAULT, CHIP_FG), NORMAL_RED);
    }

    @Override
    public Rgb planChipColor() {
        return PIPELINE_GREEN;
    }

    @Override
    public Rgb planFailColor() {
        return NORMAL_RED;
    }

    @Override
    public AttributedStyle withBackground(AttributedStyle base, Rgb bg) {
        return withBg(base, bg);
    }

    @Override
    public AttributedStyle brightYellow() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_YELLOW);
    }

    @Override
    public AttributedStyle bright(int r, int g, int b) {
        return withColor(AttributedStyle.DEFAULT, r, g, b);
    }

    @Override
    public AttributedStyle bright(Rgb c) {
        return withColor(AttributedStyle.DEFAULT, c);
    }

    // --- help-semantic styles --------------------------------------------

    @Override
    public AttributedStyle sectionHeading() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_GREEN);
    }

    @Override
    public AttributedStyle commandName() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_CYAN);
    }

    @Override
    public AttributedStyle paramLabel() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_CYAN);
    }

    @Override
    public AttributedStyle highlight() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_YELLOW);
    }

    @Override
    public AttributedStyle path() {
        return withColor(AttributedStyle.DEFAULT, PATH);
    }

    /** Neon orange for inline shell commands — web --warn amber warmed toward --err red (the web has no orange). */
    public static final Rgb SHELL_ORANGE = Rgb.hex(0xFF8329);

    @Override
    public AttributedStyle shell() {
        return withColor(AttributedStyle.DEFAULT, SHELL_ORANGE); // neon orange
    }

    // --- syntax-highlight styles -----------------------------------------

    @Override
    public AttributedStyle synKeyword() {
        return withColor(AttributedStyle.DEFAULT, GH_KEYWORD);
    }

    @Override
    public AttributedStyle synType() {
        return withColor(AttributedStyle.DEFAULT, GH_TYPE);
    }

    @Override
    public AttributedStyle synFunction() {
        return withColor(AttributedStyle.DEFAULT, GH_FUNCTION);
    }

    @Override
    public AttributedStyle synConstant() {
        return withColor(AttributedStyle.DEFAULT, GH_CONSTANT);
    }

    @Override
    public AttributedStyle synString() {
        return withColor(AttributedStyle.DEFAULT, GH_STRING);
    }

    @Override
    public AttributedStyle synNumber() {
        return withColor(AttributedStyle.DEFAULT, GH_CONSTANT);
    }

    @Override
    public AttributedStyle synComment() {
        return withColor(AttributedStyle.DEFAULT, GH_COMMENT);
    }

    @Override
    public AttributedStyle synAnnotation() {
        return withColor(AttributedStyle.DEFAULT, GH_FUNCTION);
    }

    @Override
    public AttributedStyle synNamespace() {
        return withColor(AttributedStyle.DEFAULT, GH_COMMENT);
    }

    @Override
    public AttributedStyle synPunctuation() {
        return withColor(AttributedStyle.DEFAULT, GH_COMMENT);
    }

    @Override
    public AttributedStyle errorLabel() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_RED);
    }

    /** Legacy 16-color bright-green accent for {@code tip:} lines. */
    @Override
    public String tip() {
        return "92";
    }

    /** Legacy bold-bright-cyan accent for the {@code --help} hint. */
    @Override
    public String helpHint() {
        return "1;96";
    }

    // --- gradients --------------------------------------------------------

    @Override
    public Gradient titleGradient() {
        return TITLE_GRADIENT;
    }

    @Override
    public Gradient progressGradient() {
        return PROGRESS_GRADIENT;
    }

    @Override
    public Gradient spinnerGradient() {
        return SPINNER_GRADIENT;
    }

    @Override
    public Gradient failureGradient() {
        return FAILURE_GRADIENT;
    }

    @Override
    public AttributedString gradientHeader(String text) {
        var sb = new AttributedStringBuilder();
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        if (n == 0) {
            return sb.toAttributedString();
        }
        if (!Theme.colorEnabled()) {
            // Drop the gradient entirely; bold still distinguishes the header.
            return sb.append(text, AttributedStyle.DEFAULT.bold()).toAttributedString();
        }
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            Rgb c = TITLE_GRADIENT.at(t);
            sb.append(
                    new String(Character.toChars(codepoints[i])),
                    AttributedStyle.DEFAULT.bold().foreground(c.r(), c.g(), c.b()));
        }
        return sb.toAttributedString();
    }

    @Override
    public String gradientHeaderAnsi(String text) {
        if (text.isEmpty()) return "";
        if (!Theme.colorEnabled()) return Ansi.sgr("1") + text + Ansi.RESET;
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            Rgb c = TITLE_GRADIENT.at(t);
            sb.append(Ansi.sgr(Ansi.boldFgBody(c.r(), c.g(), c.b())));
            sb.append(new String(Character.toChars(codepoints[i])));
        }
        sb.append(Ansi.RESET);
        return sb.toString();
    }

    @Override
    public AttributedStyle railStyle(Rail.StepState state, Rail.RailGlyph glyph) {
        return switch (glyph) {
            case BULLET, OPEN, MID, CLOSE ->
                switch (state) {
                    case ACTIVE -> activeStep();
                    case COMPLETED, INACTIVE -> darkGray();
                };
        };
    }
}
