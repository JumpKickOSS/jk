// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.form.Step;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Walks a {@link Form} on the terminal, step by step and field by field. A field whose {@code when}
 * is false is not shown and takes its default; an advanced field with a default takes it unasked;
 * a field answered up front (from flags) is shown settled. Every other field is asked with the
 * widget for its kind. The answers come back settled by {@link Form#check}.
 */
public final class FormRenderer {

    private final Asker asker;
    private final FormSources sources;

    public FormRenderer(Asker asker, FormSources sources) {
        this.asker = asker;
        this.sources = sources;
    }

    /** The settled values, or empty when the user backed out. {@code given} are answers from flags. */
    public Optional<Map<String, Object>> run(Form form, Map<String, ?> given) {
        Map<String, Object> values = new LinkedHashMap<>();
        RenderContext ctx = asker.context();
        for (Step step : form.steps()) {
            asker.println(
                    JkWedge.menu(form.title(), RichText.plain(step.title())).renderLine(ctx));
            for (Field f : step.fields()) {
                @Nullable Object fallback = Form.defaultOf(f, sources.derived());
                if (!Form.visible(f, values)) {
                    if (fallback != null) values.put(f.key(), fallback);
                    continue;
                }
                Object preset = given.get(f.key());
                if (preset != null && f.problem(preset) == null) {
                    values.put(f.key(), preset);
                    showGiven(Questions.of(f, values, sources), preset);
                    continue;
                }
                if (f.advanced() && fallback != null) {
                    values.put(f.key(), fallback);
                    continue;
                }
                Optional<?> answer = asker.ask(Questions.of(f, values, sources));
                if (answer.isEmpty()) return Optional.empty();
                Object value = answer.get();
                boolean blank = value instanceof String s && s.isEmpty();
                if (!blank || f.kind() == FieldKind.TEXT) values.put(f.key(), value);
            }
        }
        FormCheck checked = form.check(values, sources.derived());
        if (!checked.ok()) {
            throw new IllegalStateException(
                    checked.problems().stream().map(FormCheck.Problem::message).collect(Collectors.joining("; ")));
        }
        return Optional.of(checked.values());
    }

    /** Prints the command that gives the same answers without asking. */
    public void summary(String command) {
        RenderContext ctx = asker.context();
        Theme t = ctx.theme();
        asker.println(
                ctx.ansi()
                        ? Theme.paint("Equivalent command  ", t.darkGray()) + Theme.paint(command, t.shell())
                        : "Equivalent command: " + command);
    }

    /** {@link #summary} of {@link FormCommand#render} for {@code values}. */
    public void summary(String command, Form form, Map<String, ?> values, Map<String, FormCommand.Flag> flags) {
        summary(FormCommand.render(command, form, values, flags, sources.derived()));
    }

    @SuppressWarnings("unchecked")
    private <T> void showGiven(Question<T> question, Object value) {
        asker.given(question, (T) value);
    }
}
