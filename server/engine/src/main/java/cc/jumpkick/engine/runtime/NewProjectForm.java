// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Step;
import cc.jumpkick.form.Values;
import cc.jumpkick.giter8.JkTemplateToml;
import cc.jumpkick.scaffold.NewProjectStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The new-project form every surface renders: jk's step ({@link NewProjectStep}), then the template's
 * own {@code [[ask]]}. A template's answers become Giter8 properties; the step-1 answers become the
 * scaffold's request. Once a template is chosen the language defaults to the template's own.
 */
public final class NewProjectForm {

    public static final String ID = "new-project";

    /** The step-1 keys; every other key of the form is a template property. */
    public static final Set<String> PROJECT_KEYS = NewProjectStep.KEYS;

    private static final String TITLE = "New project";

    private NewProjectForm() {}

    /** jk's step alone: what a surface asks before a template is chosen. */
    public static Form project() {
        return new Form(ID, TITLE, List.of(NewProjectStep.of(NewProjectStep.LANGS.getFirst(), List.of(), "")));
    }

    /** jk's step and {@code template}'s own. */
    public static Form of(NewProjectOps.TemplateForm template) {
        List<Step> steps = new ArrayList<>();
        steps.add(NewProjectStep.of(language(template), template.layouts(), template.defaultLayout()));
        JkTemplateToml.Meta meta = template.meta();
        if (meta != null) meta.step().ifPresent(steps::add);
        return new Form(ID, TITLE, steps);
    }

    /** The fields that depend on the template: the layout when it is a choice, then the asks. */
    public static List<Field> templateFields(Form form) {
        List<Field> out = new ArrayList<>();
        for (Field f : form.fields()) {
            if (f.key().equals("layout") || !PROJECT_KEYS.contains(f.key())) out.add(f);
        }
        return out;
    }

    /**
     * {@code raw} typed for {@code form}: a string answer is read as its field's kind (a
     * {@code bool} from true/yes/false/no, a {@code multi} split on commas); a key the form does not
     * have is dropped.
     */
    public static Map<String, Object> typed(Form form, Map<String, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : form.fields()) {
            Object value = raw.get(f.key());
            if (value == null) continue;
            Object parsed = Values.parse(f, value);
            out.put(f.key(), parsed == null ? value : parsed);
        }
        return out;
    }

    /** The template properties among settled {@code values}, as the text a {@code -p} would carry. */
    public static Map<String, String> templateParams(Map<String, ?> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            if (PROJECT_KEYS.contains(e.getKey()) || e.getValue() == null) continue;
            out.put(e.getKey(), Values.text(e.getValue()));
        }
        return out;
    }

    /** The template's language: its id's first segment, else its descriptor's, else the default. */
    private static String language(NewProjectOps.TemplateForm template) {
        String first = template.id().split("/")[0];
        if (NewProjectStep.LANGS.contains(first)) return first;
        JkTemplateToml.Meta meta = template.meta();
        return meta != null && NewProjectStep.LANGS.contains(meta.language())
                ? meta.language()
                : NewProjectStep.LANGS.getFirst();
    }
}
