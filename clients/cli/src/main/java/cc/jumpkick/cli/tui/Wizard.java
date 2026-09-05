// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.terminal.Styled;
import cc.jumpkick.terminal.StyledBuilder;
import cc.jumpkick.terminal.TerminalSession;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Interactive wizard: {@link InputMode#PROMPT}, incremental redraw of the active step only, no
 * alt-screen (keeps the transcript). Cancels restore cursor/SGR for a clean shell prompt.
 */
public final class Wizard {

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;
    private static final String RESET_SGR = Ansi.RESET;
    private static final String CLEAR_TO_END = Ansi.ERASE_DISPLAY_TO_END;

    /** Display width of the rail prefix "│  " in columns. */
    private static final int RAIL_PREFIX_WIDTH = 3;

    /** No indent — the rail runs flush against the left edge of the terminal. */
    private static final String INDENT = "";

    /** Column count of {@link #INDENT}. */
    private static final int INDENT_COLS = 0;

    /** Poll interval for keys; lets the loop observe async cancellation flag. */
    private static final long KEY_POLL_MS = 75L;

    private final String command;
    private final String subtitle;
    private final List<WizardStep> steps;
    private volatile boolean cancelled;

    Wizard(String command, String subtitle, List<WizardStep> steps) {
        this.command = command;
        this.subtitle = subtitle;
        this.steps = steps;
    }

    public static WizardBuilder builder() {
        return new WizardBuilder();
    }

    public List<WizardStep> steps() {
        return steps;
    }

    public String command() {
        return command;
    }

    public String subtitle() {
        return subtitle;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public Optional<Answers> run(TerminalSession tty) {
        return run(tty, Answers.of(Map.of()));
    }

    /**
     * Run with pre-seeded answers. Each step whose {@code key()} is present in {@code preset} is
     * skipped interactively but rendered as completed, so the user can see what was inferred. Useful
     * when {@code jk init my-project} has supplied the "Project name" answer up front.
     */
    public Optional<Answers> run(TerminalSession tty, Answers preset) {
        if (!tty.isLive()) {
            return Optional.empty();
        }
        try (ModeGuard raw = tty.enter(InputMode.PROMPT)) {
            tty.drain(Duration.ofMillis(40));
            var writer = tty.ttyOut();
            writer.print("\r" + CLEAR_TO_END);
            var restoreHook = new Thread(() -> {
                writer.print(SHOW_CURSOR);
                writer.print(RESET_SGR);
                writer.flush();
            });
            Runtime.getRuntime().addShutdownHook(restoreHook);
            try {
                writer.print(HIDE_CURSOR);
                writer.flush();
                return Optional.of(loop(tty, preset));
            } catch (WizardCancelled e) {
                return Optional.empty();
            } finally {
                writer.print(RESET_SGR);
                writer.print(SHOW_CURSOR);
                writer.flush();
                writer.print("\r\n");
                writer.flush();
                tryRemoveHook(restoreHook);
            }
        }
    }

    /**
     * Render a Ctrl-C cancellation closer on top of the wizard's active rail: step the cursor back up
     * to the active {@code ╰} row, preserve the existing {@code ╰──} prefix (which the active region
     * already drew in cyan), and append a separator space + red {@code <message>} right after it.
     *
     * <p>Assumes the wizard has just returned {@link Optional#empty()} and that the in-loop cancel
     * path called {@link #moveBelowCloser} — so {@link #run}'s trailing {@code \r\n} places the
     * cursor two lines below the active closer.
     */
    public static void printCancellation(TerminalSession tty, String message) {
        var writer = tty.ttyOut();
        writer.print(Ansi.cursorPrevLine(2)); // up 2 lines, col 1 — lands at the active ╰
        writer.print(Ansi.cursorForward(INDENT_COLS + RAIL_PREFIX_WIDTH)); // skip past "╰──"
        writer.print(Ansi.ERASE_DISPLAY_TO_END); // erase residue beyond
        var line = new StyledBuilder()
                .append(" " + Glyphs.CROSS + " " + message, Theme.active().error()) // leading space separates from ╰──
                .build();
        writer.print(line.toAnsi());
        writer.println();
        writer.flush();
    }

    /**
     * Reposition the cursor one row below the active closer so cancellation handlers can rely on a
     * fixed cursor position regardless of step type. Input steps leave the cursor mid-region (on the
     * input buffer line); other step types leave it already below the closer.
     */
    private static void moveBelowCloser(PrintWriter writer, int regionLines, int cursorOffset) {
        int linesDown = regionLines - cursorOffset;
        if (linesDown > 0) {
            writer.print(Ansi.cursorDown(linesDown));
        }
        writer.print("\r");
        writer.flush();
    }

    private static void tryRemoveHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // shutdown in progress; nothing to do
        }
    }

    private Answers loop(TerminalSession tty, Answers preset) {
        var writer = tty.ttyOut();
        var answers = new LinkedHashMap<String, Object>(preset.asMap());

        writer.println();
        // Wizard is first chrome for interactive commands — open the blank-line envelope.
        CommandWedge.markEnvelopeStarted();
        String hdr = headerLine();
        writer.println(hdr);
        // Box opener: ╭ followed by dashes to match the header's visual width.
        int hdrWidth = visibleLength(hdr);
        writer.println(Theme.colorize(
                "╭" + "─".repeat(Math.max(0, hdrWidth - 1)), Theme.active().darkGray()));
        writer.flush();

        boolean firstStep = true;
        for (var step : steps) {
            // Pre-seeded answers skip the interactive prompt but still render
            // as settled so the user can see what was inferred up front.
            if (answers.containsKey(step.key()) && preset.has(step.key())) {
                if (!firstStep)
                    writer.println(Theme.colorize("├──────", Theme.active().darkGray()));
                firstStep = false;
                renderSettledRegion(tty, step, answers);
                writer.flush();
                continue;
            }
            if (!step.shouldRun().test(Answers.of(answers))) {
                continue;
            }
            if (!firstStep)
                writer.println(Theme.colorize("├──────", Theme.active().darkGray()));
            firstStep = false;

            var state = new ActiveState(step, answers);
            var regionLines = renderActiveRegion(tty, step, state);
            var cursorOffset = positionInputCursor(writer, step, state, regionLines);
            writer.flush();

            while (true) {
                if (cancelled) {
                    moveBelowCloser(writer, regionLines, cursorOffset);
                    throw new WizardCancelled();
                }
                var key = tty.readKey(Duration.ofMillis(KEY_POLL_MS));
                if (key.isEmpty()) {
                    if (!tty.isLive()) {
                        moveBelowCloser(writer, regionLines, cursorOffset);
                        throw new WizardCancelled();
                    }
                    continue;
                }
                if (key.get() instanceof Key.CtrlC) {
                    moveBelowCloser(writer, regionLines, cursorOffset);
                    throw new WizardCancelled();
                }
                var done = state.handle(key.get());

                writer.print(HIDE_CURSOR);
                eraseLines(writer, cursorOffset);

                if (done) {
                    state.commit(answers);
                    renderSettledRegion(tty, step, answers);
                    writer.flush();
                    break;
                }

                regionLines = renderActiveRegion(tty, step, state);
                cursorOffset = positionInputCursor(writer, step, state, regionLines);
                writer.flush();
            }
        }

        // Print the Done closer then a blank line so callers' result lines
        // have breathing room below the wizard rail. The `finally` block
        // writes a second \r\n that lands the cursor on the fresh line.
        writer.print(Theme.colorize("╰──────", Theme.active().darkGray()));
        writer.print("\r\n");
        writer.flush();
        return Answers.of(Map.copyOf(answers));
    }

    private int renderActiveRegion(TerminalSession tty, WizardStep step, ActiveState state) {
        var writer = tty.ttyOut();
        writer.println(
                INDENT + Rail.stepBullet(Rail.StepState.ACTIVE, step.prompt()).toAnsi());
        var interactive = state.render();
        for (var line : interactive) {
            writer.println(INDENT + Rail.mid(line, Rail.StepState.ACTIVE).toAnsi());
        }
        // └ hook one line below the interactive content; gets erased on commit
        // and re-emitted (with the next step's content above it) on each step.
        writer.println(
                Theme.colorize("╰──────", Theme.active().railStyle(Rail.StepState.ACTIVE, Rail.RailGlyph.CLOSE)));
        return 1 + interactive.size() + 1;
    }

    private void renderSettledRegion(TerminalSession tty, WizardStep step, Map<String, Object> answers) {
        var writer = tty.ttyOut();
        writer.println(INDENT
                + Rail.stepBullet(Rail.StepState.COMPLETED, step.prompt()).toAnsi());
        for (var line : summarize(step, answers)) {
            writer.println(INDENT + Rail.mid(line, Rail.StepState.COMPLETED).toAnsi());
        }
    }

    private void eraseLines(PrintWriter writer, int rows) {
        if (rows <= 0) {
            // Still need to return to column 0 so the redraw doesn't prepend onto
            // whatever was to the left of the cursor (e.g., an input buffer).
            writer.print("\r");
            writer.print(CLEAR_TO_END);
            return;
        }
        // ESC[<n>F = cursor previous line: moves up n lines AND to column 0.
        // (ESC[<n>A only moves up, preserving column — that left the redraw
        // starting mid-line and pasting the new bullet onto the old text.)
        writer.print(Ansi.cursorPrevLine(rows));
        writer.print(CLEAR_TO_END);
    }

    /**
     * Positions the cursor for input steps and returns the cursor's offset (in lines) from the top of
     * the active region. The caller must pass this value back to {@link #eraseLines} so the redraw
     * moves up by the correct amount instead of climbing past the region origin and eating prior
     * terminal content.
     */
    private int positionInputCursor(PrintWriter writer, WizardStep step, ActiveState state, int regionLines) {
        if (!(step instanceof WizardStep.InputStep)) {
            // Cursor sits below the region after the final println.
            return regionLines;
        }
        // After emitting (bullet + interactive) lines, the cursor sits at the start
        // of the line BELOW the region. The input buffer line is the second line of
        // the region (line index 1: bullet is line 0). Move the cursor up to that
        // line, then to the column just past the last typed character.
        var linesUp = regionLines - 1;
        if (linesUp > 0) {
            writer.print(Ansi.cursorUp(linesUp));
        }
        var col = INDENT_COLS + RAIL_PREFIX_WIDTH + state.input.length() + 1;
        writer.print(Ansi.cursorToColumn(col));
        writer.print(SHOW_CURSOR);
        return 1;
    }

    /**
     * Build the wizard header as a three-line rounded box:
     *
     * <pre>
     *   ╭──────────────────────────────────────────╮
     *   │ ≡ Command ▶ [gray-band] Subtitle [/band]   │
     *   ├──────────────────────────────────────────╯
     * </pre>
     *
     * <p>The middle line is exactly the original chip+subtitle content (Nerd Font cap, gray
     * background band, etc.) — unchanged. The box borders are in {@code darkGray} and
     * auto-size to the content's visible (print-column) width.
     */
    /**
     * Header line: menu wedge chip followed by the subtitle in bold-white (focused). The
     * {@code ╭──} opener is printed separately in {@link #loop} to match this line's visual width.
     */
    private String headerLine() {
        String sub = subtitle == null ? "" : subtitle;
        RichText tail = sub.isEmpty()
                ? RichText.empty()
                : RichText.ansi(Theme.colorize(sub, Theme.active().focused()));
        return new JkWedge(Icon.menu(), command, tail)
                .variant(JkWedge.Variant.MENU)
                .renderLine(RenderContext.current());
    }

    /** Visible (print-column) length of {@code s}: CSI and OSC stripped, raw chars counted. */
    private static int visibleLength(String s) {
        return RenderContext.stripAnsi(s).length();
    }

    private static List<Styled> summarize(WizardStep step, Map<String, Object> answers) {
        var answerStyle = Theme.active().settled().italic();
        return switch (step) {
            case WizardStep.InputStep is ->
                List.of(answerLine(answers.getOrDefault(is.key(), "").toString(), answerStyle));
            case WizardStep.RadioStep rs -> List.of(answerLine(labelFor(rs, answers), answerStyle));
            case WizardStep.MultiSelectStep ms -> {
                @SuppressWarnings("unchecked")
                var selected = (List<String>) answers.getOrDefault(ms.key(), List.<String>of());
                if (selected.isEmpty()) {
                    yield List.of(answerLine("(none selected)", answerStyle));
                }
                // Map known choice ids to their labels; entries with no match
                // (a free-form custom value) render verbatim. Iterate the
                // stored list so selection order — including the appended
                // custom value — is preserved.
                var byId = new HashMap<String, String>();
                for (var c : ms.choicesFor(Answers.of(answers))) {
                    byId.put(c.id(), c.label());
                }
                var labels = new ArrayList<Styled>();
                for (var v : selected) {
                    labels.add(answerLine(byId.getOrDefault(v, v), answerStyle));
                }
                yield labels;
            }
            case WizardStep.OutputStep os ->
                os.render().apply(Answers.of(answers)).stream()
                        .map(s -> plain(s, Theme.active().darkGray()))
                        .toList();
        };
    }

    private static Styled answerLine(@Nullable String text, Style textStyle) {
        return new StyledBuilder()
                .append("➜ ", Theme.active().brightGreen())
                .append(text, textStyle)
                .build();
    }

    private static @Nullable String labelFor(WizardStep.RadioStep step, Map<String, Object> answers) {
        var snapshot = Answers.of(answers);
        var id = answers.getOrDefault(step.key(), step.defaultChoice()).toString();
        for (var c : step.choicesFor(snapshot)) {
            if (c.id().equals(id)) {
                return c.label();
            }
        }
        return id;
    }

    private static Styled plain(String text, Style style) {
        return new StyledBuilder().append(text, style).build();
    }

    /**
     * Per-step interactive state. Created at the start of each step iteration and torn down once
     * {@link #handle(Key)} reports completion.
     */
    private static final class ActiveState {

        private final WizardStep step;
        private final StringBuilder input;
        /** Type-to-filter buffer for filterable multi-select steps. */
        private final StringBuilder filter = new StringBuilder();

        private int focus;
        private final LinkedHashSet<String> selected;
        private final Answers snapshot;
        private String error = "";

        ActiveState(WizardStep step, Map<String, Object> existing) {
            this.step = step;
            this.input = new StringBuilder();
            this.selected = new LinkedHashSet<>();
            // Snapshot answers as of step entry — used to resolve dynamic
            // Choice hints (Choice.hintFn) so a later step's options can
            // reflect what the user picked on an earlier step.
            this.snapshot = Answers.of(Map.copyOf(existing));
            switch (step) {
                case WizardStep.InputStep is -> {
                    var prior = existing.get(is.key());
                    if (prior != null) {
                        this.input.append(prior);
                    } else {
                        var seed = is.initialValueFor(snapshot);
                        if (seed != null && !seed.isEmpty()) {
                            this.input.append(seed);
                        }
                    }
                }
                case WizardStep.RadioStep rs -> {
                    var idx = indexOf(rs.choicesFor(snapshot), rs.defaultChoice());
                    this.focus = Math.max(0, idx);
                }
                case WizardStep.MultiSelectStep ms -> {
                    this.focus = 0;
                    this.selected.addAll(ms.defaults());
                }
                case WizardStep.OutputStep os -> {}
            }
        }

        /** Visible multi-select rows after optional type-to-filter. */
        private List<Choice> multiVisible(WizardStep.MultiSelectStep ms) {
            List<Choice> all = ms.choicesFor(snapshot);
            if (!ms.filterable() || filter.isEmpty()) return all;
            String q = filter.toString().toLowerCase(Locale.ROOT);
            var out = new ArrayList<Choice>();
            for (var c : all) {
                if (c.id().toLowerCase(Locale.ROOT).contains(q)
                        || c.label().toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(c);
                }
            }
            return out;
        }

        private static int indexOf(List<Choice> choices, @Nullable String id) {
            for (var i = 0; i < choices.size(); i++) {
                if (choices.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        /** Returns true when the step is complete and Wizard should advance. */
        boolean handle(Key key) {
            return switch (step) {
                case WizardStep.InputStep is -> handleInput(is, key);
                case WizardStep.RadioStep rs -> handleRadio(rs, key);
                case WizardStep.MultiSelectStep ms -> handleMulti(ms, key);
                case WizardStep.OutputStep os -> key instanceof Key.Enter;
            };
        }

        private boolean handleInput(WizardStep.InputStep is, Key key) {
            return switch (key) {
                case Key.Enter e -> {
                    var value = input.length() == 0 ? is.defaultValue() : input.toString();
                    var result = is.validator().apply(value);
                    if (result instanceof ValidationResult.Error err) {
                        error = err.message();
                        yield false;
                    }
                    input.setLength(0);
                    input.append(value);
                    error = "";
                    yield true;
                }
                case Key.Right r -> realizePlaceholder(is);
                case Key.Tab t -> realizePlaceholder(is);
                case Key.Backspace b -> {
                    if (input.length() > 0) {
                        input.deleteCharAt(input.length() - 1);
                    }
                    error = "";
                    yield false;
                }
                case Key.Space s -> {
                    input.append(' ');
                    error = "";
                    yield false;
                }
                case Key.Char(char c) -> {
                    input.append(c);
                    error = "";
                    yield false;
                }
                default -> false;
            };
        }

        private boolean realizePlaceholder(WizardStep.InputStep is) {
            // Realize the placeholder as if the user typed it. The text picks
            // up the normal "user input" styling — there's no visual distinction
            // between typed-and-accepted text.
            if (input.length() == 0 && !is.placeholder().isEmpty()) {
                input.append(is.placeholder());
                error = "";
            }
            return false;
        }

        private boolean handleRadio(WizardStep.RadioStep rs, Key key) {
            int choiceCount = rs.choicesFor(snapshot).size();
            boolean customEnabled = rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL;
            int size = choiceCount + (customEnabled ? 1 : 0);
            boolean onCustom = customEnabled && focus == choiceCount;
            return switch (key) {
                case Key.Enter e -> {
                    if (onCustom && input.length() == 0) {
                        error = "Type a value or pick an option above.";
                        yield false;
                    }
                    error = "";
                    yield true;
                }
                case Key.Up u -> moveFocus(-1, size, rs.orientation() == Orientation.VERTICAL);
                case Key.Down d -> moveFocus(1, size, rs.orientation() == Orientation.VERTICAL);
                case Key.Left l -> moveFocus(-1, size, rs.orientation() == Orientation.HORIZONTAL);
                case Key.Right r -> moveFocus(1, size, rs.orientation() == Orientation.HORIZONTAL);
                case Key.Backspace b
                when onCustom -> {
                    if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                    error = "";
                    yield false;
                }
                case Key.Space s
                when onCustom -> {
                    input.append(' ');
                    error = "";
                    yield false;
                }
                case Key.Char(char c)
                when onCustom -> {
                    input.append(c);
                    error = "";
                    yield false;
                }
                default -> false;
            };
        }

        private boolean moveFocus(int delta, int size, boolean enabled) {
            if (!enabled || size == 0) {
                return false;
            }
            focus = ((focus + delta) % size + size) % size;
            return false;
        }

        private boolean handleMulti(WizardStep.MultiSelectStep ms, Key key) {
            List<Choice> visible = multiVisible(ms);
            int choiceCount = visible.size();
            boolean customEnabled = ms.hasCustomOption() && ms.orientation() == Orientation.VERTICAL;
            int size = choiceCount + (customEnabled ? 1 : 0);
            if (size > 0 && focus >= size) focus = size - 1;
            boolean onCustom = customEnabled && focus == choiceCount;
            return switch (key) {
                case Key.Enter e -> true;
                // On the free-form row, Space / chars / Backspace edit the
                // buffer instead of toggling — the row is "checked" whenever
                // it holds text, so there's nothing to toggle.
                case Key.Backspace b
                when onCustom -> {
                    if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                    yield false;
                }
                case Key.Backspace b
                when ms.filterable() && !onCustom && filter.length() > 0 -> {
                    filter.deleteCharAt(filter.length() - 1);
                    focus = 0;
                    yield false;
                }
                case Key.Space s -> {
                    if (onCustom) {
                        input.append(' ');
                    } else if (!visible.isEmpty()) {
                        var c = visible.get(focus);
                        if (!selected.add(c.id())) {
                            selected.remove(c.id());
                        }
                    }
                    yield false;
                }
                case Key.Char(char ch) -> {
                    if (onCustom) {
                        input.append(ch);
                    } else if (ms.filterable()) {
                        // Type-to-filter; do not hijack 'a' for select-all.
                        filter.append(ch);
                        focus = 0;
                    } else if (ch == 'a') {
                        List<Choice> all = ms.choicesFor(snapshot);
                        if (selected.size() == all.size()) {
                            selected.clear();
                        } else {
                            for (var c : all) {
                                selected.add(c.id());
                            }
                        }
                    }
                    yield false;
                }
                case Key.Up u -> moveFocus(-1, size, ms.orientation() == Orientation.VERTICAL);
                case Key.Down d -> moveFocus(1, size, ms.orientation() == Orientation.VERTICAL);
                case Key.Left l -> moveFocus(-1, size, ms.orientation() == Orientation.HORIZONTAL);
                case Key.Right r -> moveFocus(1, size, ms.orientation() == Orientation.HORIZONTAL);
                default -> false;
            };
        }

        void commit(Map<String, Object> answers) {
            switch (step) {
                case WizardStep.InputStep is -> answers.put(is.key(), input.toString());
                case WizardStep.RadioStep rs -> {
                    var choices = rs.choicesFor(snapshot);
                    boolean customEnabled = rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL;
                    if (customEnabled && focus == choices.size()) {
                        answers.put(rs.key(), input.toString());
                    } else {
                        answers.put(rs.key(), choices.get(focus).id());
                    }
                }
                case WizardStep.MultiSelectStep ms -> {
                    var ordered = new ArrayList<String>();
                    for (var c : ms.choicesFor(snapshot)) {
                        if (selected.contains(c.id())) {
                            ordered.add(c.id());
                        }
                    }
                    boolean customEnabled = ms.hasCustomOption() && ms.orientation() == Orientation.VERTICAL;
                    if (customEnabled && !input.toString().isBlank()) {
                        ordered.add(input.toString());
                    }
                    answers.put(ms.key(), List.copyOf(ordered));
                }
                case WizardStep.OutputStep os -> {
                    // Output steps have no answer; they only gate progression.
                }
            }
        }

        List<Styled> render() {
            return switch (step) {
                case WizardStep.InputStep is -> renderInput(is);
                case WizardStep.RadioStep rs -> renderRadio(rs);
                case WizardStep.MultiSelectStep ms -> renderMulti(ms);
                case WizardStep.OutputStep os -> renderOutput(os);
            };
        }

        private List<Styled> renderInput(WizardStep.InputStep is) {
            return ansiLines(
                    new TextInput(input.toString(), is.placeholder(), true, error).render(RenderContext.current()));
        }

        private List<Styled> renderRadio(WizardStep.RadioStep rs) {
            var choices = rs.choicesFor(snapshot);
            var buttons = new ArrayList<RadioButton>();
            for (int i = 0; i < choices.size(); i++) {
                var c = choices.get(i);
                boolean isFocused = i == focus;
                buttons.add(new RadioButton(c.label(), isFocused, isFocused, c.hintFor(snapshot)));
            }
            if (rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL) {
                boolean isFocused = focus == choices.size();
                String custom = input.length() == 0 ? rs.customPlaceholder() : input.toString();
                buttons.add(new RadioButton(custom, isFocused, isFocused, ""));
            }
            var lines = new ArrayList<>(
                    ansiLines(new RadioButtonGroup(buttons, rs.orientation()).render(RenderContext.current())));
            if (rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL && !error.isEmpty()) {
                lines.add(Styled.of(Theme.paint(error, Theme.active().error()), Style.EMPTY));
            }
            return lines;
        }

        private static List<Styled> ansiLines(List<String> lines) {
            var out = new ArrayList<Styled>(lines.size());
            for (String line : lines) {
                out.add(Styled.of(line, Style.EMPTY));
            }
            return out;
        }

        /**
         * Render the editable free-form field: the placeholder as dim italic example text while empty
         * (overwrite-able), or the typed text in the focused/dim style once the user starts typing.
         */
        private void appendCustomField(StyledBuilder sb, boolean focused, String placeholder) {
            if (input.length() == 0) {
                sb.append(placeholder, Theme.active().darkGray().italic());
            } else {
                sb.append(
                        input.toString(),
                        focused ? Theme.active().focused() : Theme.active().darkGray());
            }
        }

        private void appendError(List<Styled> lines) {
            if (!error.isEmpty()) {
                lines.add(new StyledBuilder()
                        .append(error, Theme.active().error())
                        .build());
            }
        }

        private static void appendHint(StyledBuilder sb, String hint) {
            if (hint == null || hint.isEmpty()) return;
            sb.append("  ");
            sb.append(hint, Theme.active().darkGray());
        }

        private List<Styled> renderMulti(WizardStep.MultiSelectStep ms) {
            var lines = new ArrayList<Styled>();
            List<Choice> visible = multiVisible(ms);
            if (ms.filterable() && ms.orientation() == Orientation.VERTICAL) {
                var fsb = new StyledBuilder()
                        .append("filter: ", Theme.active().darkGray())
                        .append(
                                filter.length() == 0 ? "(type to search)" : filter.toString(),
                                filter.length() == 0
                                        ? Theme.active().darkGray().italic()
                                        : Theme.active().focused());
                if (!selected.isEmpty()) {
                    fsb.append("  ·  ", Theme.active().darkGray())
                            .append(
                                    selected.size() + " selected",
                                    Theme.active().completedStep());
                }
                lines.add(fsb.build());
            }
            if (ms.orientation() == Orientation.VERTICAL) {
                // Cap rows so tall catalogs stay usable under type-to-filter.
                int maxShow = ms.filterable() ? 12 : Integer.MAX_VALUE;
                int shown = 0;
                for (var i = 0; i < visible.size() && shown < maxShow; i++) {
                    var c = visible.get(i);
                    var isFocused = i == focus;
                    var isChecked = selected.contains(c.id());
                    if (c.richLabelFn() != null) {
                        var sb = new StyledBuilder()
                                .append(
                                        isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF,
                                        isChecked
                                                ? Theme.active().completedStep()
                                                : (isFocused
                                                        ? Theme.active().activeStep()
                                                        : Theme.active().darkGray()))
                                .append("  ")
                                .append(c.richLabelFn().apply(isFocused));
                        appendHint(sb, c.hintFor(snapshot));
                        lines.add(sb.build());
                    } else {
                        lines.addAll(ansiLines(new Checkbox(c.label(), isChecked, isFocused, c.hintFor(snapshot))
                                .render(RenderContext.current())));
                    }
                    shown++;
                }
                if (visible.size() > maxShow) {
                    lines.add(new StyledBuilder()
                            .append(
                                    "  … +" + (visible.size() - maxShow) + " more (type to filter)",
                                    Theme.active().darkGray().italic())
                            .build());
                }
                if (ms.hasCustomOption()) {
                    var isFocused = focus == visible.size();
                    var isChecked = input.length() > 0; // checked while it holds text
                    var glyph = isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF;
                    var glyphStyle = isChecked
                            ? Theme.active().completedStep()
                            : (isFocused
                                    ? Theme.active().activeStep()
                                    : Theme.active().darkGray());
                    var sb = new StyledBuilder().append(glyph, glyphStyle).append("  ");
                    appendCustomField(sb, isFocused, ms.customPlaceholder());
                    lines.add(sb.build());
                }
            } else {
                var sb = new StyledBuilder();
                for (var i = 0; i < visible.size(); i++) {
                    var c = visible.get(i);
                    var isFocused = i == focus;
                    var isChecked = selected.contains(c.id());
                    var glyphStyle = isChecked
                            ? Theme.active().completedStep()
                            : (isFocused
                                    ? Theme.active().activeStep()
                                    : Theme.active().darkGray());
                    var labelStyle = isFocused
                            ? Theme.active().focused()
                            : Theme.active().darkGray();
                    sb.append(isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF, glyphStyle);
                    sb.append("  ");
                    sb.append(c.label(), labelStyle);
                    appendHint(sb, c.hintFor(snapshot));
                    if (i < visible.size() - 1) {
                        sb.append("  ");
                    }
                }
                lines.add(sb.build());
            }
            return lines;
        }

        private List<Styled> renderOutput(WizardStep.OutputStep os) {
            var pieces = os.render().apply(Answers.of(Map.of()));
            var lines = new ArrayList<Styled>();
            for (var s : pieces) {
                lines.add(plain(s, Theme.active().darkGray()));
            }
            return lines;
        }
    }
}
