// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The forms decisions ask. A confirmation is one required choice, {@value #ANSWER}: {@value #YES}
 * proceeds, {@value #NO} declines, and {@value #SHOW}, offered only where the host can open a link,
 * moves the question to the dashboard. A batch puts {@value #CHANGES} before it: one checkbox per
 * change, each labelled with what it does and helped by why and its effect, all ticked to start;
 * {@value #YES} applies the ticked ones.
 */
public final class DecisionForms {

    /** The confirmation's one field. */
    public static final String ANSWER = "answer";

    /** A batch's checklist. */
    public static final String CHANGES = "changes";

    public static final String YES = "yes";
    public static final String NO = "no";
    public static final String SHOW = "show";

    /** One change a batch lists: {@code label} says what it does, {@code help} why and its effect. */
    public record Item(String id, String label, String help) {}

    private DecisionForms() {}

    /** {@code title} with a yes that says what proceeding does. */
    public static Form confirm(String kind, String title, String yes) {
        return Form.of(kind, oneLine(title), Step.of("decision", "Decision", answer(yes, false)));
    }

    /** {@code title} with one ticked checkbox per item, then the confirmation's answer. */
    public static Form batch(String kind, String title, List<Item> items, String yes) {
        if (items.isEmpty()) return confirm(kind, title, yes);
        Field.Builder changes = Field.of(CHANGES, "Changes", FieldKind.MULTI).required(true);
        List<String> all = new ArrayList<>();
        for (Item item : items) {
            changes.option(item.id(), oneLine(item.label()), oneLine(item.help()));
            all.add(item.id());
        }
        changes.defaultValue(all);
        return Form.of(kind, oneLine(title), Step.of("decision", "Decision", changes.build(), answer(yes, false)));
    }

    /**
     * {@code form} with the option that opens the dashboard added to its answer, for a host that can
     * open a link; its other fields stay as they are.
     */
    public static Form confirmOrShow(Form form) {
        List<Field> fields = new ArrayList<>();
        for (Field f : form.steps().getFirst().fields()) {
            if (!f.key().equals(ANSWER)) {
                fields.add(f);
                continue;
            }
            String yes = f.options().getFirst().label();
            fields.add(answer(yes == null ? "Yes" : yes, true));
        }
        return Form.of(form.id(), form.title(), Step.of("decision", "Decision", fields.toArray(Field[]::new)));
    }

    /** True when {@code answers} settle a confirmation as declined. */
    public static boolean declines(Map<String, Object> answers) {
        return NO.equals(answers.get(ANSWER));
    }

    /** True when {@code answers} ask to see the decision in the dashboard. */
    public static boolean shows(Map<String, Object> answers) {
        return SHOW.equals(answers.get(ANSWER));
    }

    /** The changes a batch's answers ticked, in form order; empty for a confirmation. */
    public static List<String> chosen(Map<String, Object> answers) {
        List<String> out = new ArrayList<>();
        if (answers.get(CHANGES) instanceof List<?> list) for (Object id : list) out.add(String.valueOf(id));
        return out;
    }

    private static Field answer(String yes, boolean show) {
        Field.Builder b = Field.of(ANSWER, "Answer", FieldKind.CHOICE)
                .option(YES, oneLine(yes), null)
                .option(NO, "No, leave it as it is", null);
        if (show) b.option(SHOW, "Show me the details (opens the dashboard)", null);
        return b.build();
    }

    private static String oneLine(String s) {
        String flat = s.replace('\r', ' ').replace('\n', ' ').strip();
        return flat.isEmpty() ? "Decision" : flat;
    }
}
