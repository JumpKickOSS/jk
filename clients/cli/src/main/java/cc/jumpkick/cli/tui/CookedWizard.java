// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Line-oriented driver behind {@link Wizard#run} for a live terminal that must not see escape
 * sequences ({@code --no-ansi}, {@code TERM=dumb}). Questions are plain lines on stderr, answers
 * are whole lines read from stdin in the terminal's own cooked mode, and the transcript is
 * append-only: a settled step is never repainted. Step keys, defaults and validators are the ones
 * the ANSI loop uses, so a caller cannot tell which driver answered.
 */
final class CookedWizard {

    /** Plain-mode counterpart of the ANSI settle arrow. */
    static final String SETTLED_PREFIX = "-> ";

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[\\s,]+");

    private final Wizard wizard;
    private final BufferedReader in;
    private final PrintStream err;

    CookedWizard(Wizard wizard) {
        this(wizard, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), CliOutput.stderr());
    }

    CookedWizard(Wizard wizard, BufferedReader in, PrintStream err) {
        this.wizard = wizard;
        this.in = in;
        this.err = err;
    }

    /** Empty when stdin ends before the last step is answered. */
    Optional<Answers> run(Answers preset) {
        var answers = new LinkedHashMap<String, Object>(preset.asMap());
        err.println(wizard.headerLine());
        try {
            for (var step : wizard.steps()) {
                boolean settledUpFront = answers.containsKey(step.key()) && preset.has(step.key());
                if (!settledUpFront && !step.shouldRun().test(Answers.of(answers))) {
                    continue;
                }
                err.println();
                if (settledUpFront) {
                    err.println(step.prompt());
                } else {
                    ask(step, answers);
                }
                settle(step, answers);
            }
        } catch (WizardCancelled e) {
            // EOF leaves the cursor after the prompt; end the line so the shell prompt starts clean.
            err.println();
            return Optional.empty();
        }
        // Breathing room under the last answer, where the ANSI path draws its closer.
        err.println();
        return Optional.of(Answers.of(Map.copyOf(answers)));
    }

    private void ask(WizardStep step, Map<String, Object> answers) {
        var snapshot = Answers.of(Map.copyOf(answers));
        switch (step) {
            case WizardStep.InputStep is -> answers.put(is.key(), askInput(is, snapshot));
            case WizardStep.RadioStep rs -> answers.put(rs.key(), askRadio(rs, snapshot));
            case WizardStep.MultiSelectStep ms -> answers.put(ms.key(), askMulti(ms, snapshot));
            case WizardStep.OutputStep os -> {
                if (!os.prompt().isEmpty()) err.println(os.prompt());
            }
        }
    }

    private void settle(WizardStep step, Map<String, Object> answers) {
        String prefix = step instanceof WizardStep.OutputStep ? "  " : SETTLED_PREFIX;
        for (var text : Wizard.settledTexts(step, answers)) {
            err.println(prefix + text);
        }
    }

    /** Empty input takes the seeded value, then the default; the validator re-asks on failure. */
    private String askInput(WizardStep.InputStep is, Answers snapshot) {
        String seed = is.initialValueFor(snapshot);
        String fallback = seed.isEmpty() ? is.defaultValue() : seed;
        String hint = !fallback.isEmpty()
                ? " [" + fallback + "]"
                : is.placeholder().isEmpty() ? "" : " (e.g. " + is.placeholder() + ")";
        while (true) {
            String typed = readLine(question(is.prompt()) + hint + ": ");
            String value = typed.isEmpty() ? fallback : typed;
            if (is.validator().apply(value) instanceof ValidationResult.Error error) {
                err.println(error.message());
                continue;
            }
            return value;
        }
    }

    /** Numbered menu; the answer is a position, an id or a label, empty for the default. */
    private String askRadio(WizardStep.RadioStep rs, Answers snapshot) {
        var choices = rs.choicesFor(snapshot);
        boolean custom = rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL;
        int preselected = Math.max(0, indexOfId(choices, rs.defaultChoice()));
        err.println(rs.prompt());
        var ctx = plainContext();
        for (int i = 0; i < choices.size(); i++) {
            var c = choices.get(i);
            var button = new RadioButton(c.label(), i == preselected, false, c.hintFor(snapshot));
            err.println(row(i + 1, button.renderInline(ctx)));
        }
        if (custom) err.println(customRow(rs.customPlaceholder()));
        if (choices.isEmpty() && !custom) return "";
        String hint = choices.isEmpty() ? "" : " [" + (preselected + 1) + "]";
        while (true) {
            String typed = readLine("Select" + hint + ": ");
            if (typed.isEmpty() && !choices.isEmpty())
                return choices.get(preselected).id();
            int idx = indexOfToken(choices, typed);
            if (idx >= 0) return choices.get(idx).id();
            if (custom && !typed.isEmpty()) return typed;
            err.println(rejection(choices.size()));
        }
    }

    /** Numbered checklist; the answer is a list of positions or ids, {@code all}, {@code none}, or empty. */
    private List<String> askMulti(WizardStep.MultiSelectStep ms, Answers snapshot) {
        var choices = ms.choicesFor(snapshot);
        boolean custom = ms.hasCustomOption() && ms.orientation() == Orientation.VERTICAL;
        err.println(ms.prompt());
        var ctx = plainContext();
        var preselected = new ArrayList<String>();
        for (int i = 0; i < choices.size(); i++) {
            var c = choices.get(i);
            boolean checked = ms.defaults().contains(c.id());
            if (checked) preselected.add(String.valueOf(i + 1));
            var box = new Checkbox(c.label(), checked, false, c.hintFor(snapshot));
            err.println(row(i + 1, box.render(ctx).getFirst()));
        }
        if (custom) err.println(customRow(ms.customPlaceholder()));
        String hint = " [" + (preselected.isEmpty() ? "none" : String.join(" ", preselected)) + "]";
        while (true) {
            String typed = readLine("Select (e.g. 1 3, all, none)" + hint + ": ");
            var picked = parseMulti(typed, choices, ms.defaults(), custom);
            if (picked != null) return picked;
            err.println(rejection(choices.size()));
        }
    }

    /**
     * Selected ids in choice order, custom tokens appended in typed order — the shape the ANSI loop
     * commits. Null when a token is neither a position, an id, nor (where allowed) a custom value.
     */
    static @Nullable List<String> parseMulti(String typed, List<Choice> choices, Set<String> defaults, boolean custom) {
        var selected = new LinkedHashSet<String>();
        var extra = new ArrayList<String>();
        String lower = typed.toLowerCase(Locale.ROOT);
        if (typed.isEmpty()) {
            selected.addAll(defaults);
        } else if (lower.equals("all")) {
            for (var c : choices) selected.add(c.id());
        } else if (!lower.equals("none")) {
            for (String token : TOKEN_SEPARATOR.split(typed)) {
                if (token.isEmpty()) continue;
                int idx = indexOfToken(choices, token);
                if (idx >= 0) {
                    selected.add(choices.get(idx).id());
                } else if (custom) {
                    extra.add(token);
                } else {
                    return null;
                }
            }
        }
        var ordered = new ArrayList<String>();
        for (var c : choices) {
            if (selected.contains(c.id())) ordered.add(c.id());
        }
        ordered.addAll(extra);
        return List.copyOf(ordered);
    }

    /** Index of {@code token} as a 1-based position, then as an id, then as a label; -1 when none. */
    static int indexOfToken(List<Choice> choices, String token) {
        try {
            int n = Integer.parseInt(token);
            if (n >= 1 && n <= choices.size()) return n - 1;
        } catch (NumberFormatException ignored) {
            // not a position
        }
        int byId = indexOfId(choices, token);
        if (byId >= 0) return byId;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).label().equalsIgnoreCase(token)) return i;
        }
        return -1;
    }

    private static int indexOfId(List<Choice> choices, @Nullable String id) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id().equalsIgnoreCase(id)) return i;
        }
        return -1;
    }

    private String readLine(String prompt) {
        err.print(prompt);
        err.flush();
        try {
            String line = in.readLine();
            if (line == null) throw new WizardCancelled();
            return line.trim();
        } catch (IOException e) {
            throw new WizardCancelled();
        }
    }

    private static RenderContext plainContext() {
        return RenderContext.current().withAnsi(false);
    }

    /** The prompt without its trailing colon, so the hint and colon can follow it. */
    private static String question(String prompt) {
        String q = prompt.strip();
        return q.endsWith(":") ? q.substring(0, q.length() - 1).stripTrailing() : q;
    }

    private static String row(int position, String body) {
        return String.format(Locale.ROOT, "%3d) %s", position, body);
    }

    private static String customRow(String placeholder) {
        return "     (or type your own: " + placeholder + ")";
    }

    private static String rejection(int count) {
        return count == 0 ? "Type a value." : "Pick 1-" + count + " or a listed id.";
    }
}
