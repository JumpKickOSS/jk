// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.scaffold.NewProjectStep;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Answers posted to the new-project form, as the dashboard's wizard posts them: one map of form key
 * to answer, and the {@code rev} of the form they were collected against. The answers' template and
 * language name the form; a {@code rev} that no longer matches it is stale, and answers that do not
 * settle it are refused, field by field. A settled post is wired for the engine's agents setting
 * ({@link NewProjectAgents}), as {@code jk new} wires for its shell's.
 */
public final class NewProjectPost {

    private NewProjectPost() {}

    /** What a post comes to. */
    public sealed interface Outcome {
        /** The scaffold request, and the template id the answers' ref resolved to. */
        record Ready(NewProjectOps.Request request, String template) implements Outcome {}

        /** The form changed since the answers were collected; {@code form} is the one in force. */
        record Stale(Form form, String template, String message) implements Outcome {}

        /** The answers do not settle the form; each problem names its field. */
        record Refused(List<FormCheck.Problem> problems) implements Outcome {}
    }

    /**
     * Settles {@code answers} against the form their {@code template} (none: the default one) and
     * {@code lang} name. A null {@code rev} is not checked. {@code derived} holds the values of the
     * form's derived defaults ({@link NewProjectStep#GIT_EMAIL}, {@link NewProjectStep#PARENT_DIR}),
     * which a left-out answer takes.
     *
     * @throws IllegalArgumentException for a template that does not resolve, or a {@code dir} that
     *     is not a path
     */
    public static Outcome settle(Map<String, ?> answers, @Nullable String rev, Map<String, String> derived)
            throws IOException {
        Map<String, Object> given = new LinkedHashMap<>(answers);
        Path cwd = cwd(given.get("dir"), derived);
        NewProjectOps.TemplateForm t = NewProjectOps.templateForm(
                orDefault(text(given.get("template"))), blankToNull(text(given.get("lang"))), cwd);
        Form form = NewProjectForm.of(t);
        if (rev != null && !rev.equals(form.rev())) {
            return new Outcome.Stale(
                    form, t.id(), "the form changed since it was read (rev " + rev + ", now " + form.rev() + ")");
        }
        // The posted template may be a ref the form resolved; the form's own id is the answer.
        given.put("template", t.id());
        List<FormCheck.Problem> problems = new ArrayList<>();
        for (String key : given.keySet()) {
            if (form.field(key) == null)
                problems.add(new FormCheck.Problem(key, "'" + key + "' is not a field of this form"));
        }
        FormCheck check = form.check(NewProjectForm.typed(form, given), derived::get);
        problems.addAll(check.problems());
        if (!problems.isEmpty()) return new Outcome.Refused(problems);
        // The wizard has no shell of its own: its project is wired for the engine's agents setting.
        return new Outcome.Ready(
                NewProjectForm.request(check.values(), t.id(), cwd).withAgents(NewProjectAgents.current()), t.id());
    }

    /** The form a surface shows before anything is answered: {@code template}'s (none: the default one). */
    public static NewProjectOps.TemplateForm templateForm(
            @Nullable String template, @Nullable String lang, Map<String, String> derived) throws IOException {
        return NewProjectOps.templateForm(orDefault(template), blankToNull(lang), Path.of(parentDir(derived)));
    }

    /** Where a relative template path resolves: the directory answer, else the derived parent. */
    private static Path cwd(@Nullable Object dir, Map<String, String> derived) {
        String d = text(dir);
        return PathUtil.resolveUserPath(d == null || d.isBlank() ? parentDir(derived) : d);
    }

    private static String parentDir(Map<String, String> derived) {
        return Objects.requireNonNull(derived.get(NewProjectStep.PARENT_DIR), NewProjectStep.PARENT_DIR);
    }

    private static String orDefault(@Nullable String template) {
        return template == null || template.isBlank() ? NewProjectStep.DEFAULT_TEMPLATE : template.strip();
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
