// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Step;
import java.util.Map;

/**
 * The forms decisions ask. A confirmation is one required choice, {@value #ANSWER}: {@value #YES}
 * proceeds, {@value #NO} declines, and {@value #SHOW}, offered only where the host can open a link,
 * moves the question to the dashboard.
 */
public final class DecisionForms {

    /** The confirmation's one field. */
    public static final String ANSWER = "answer";

    public static final String YES = "yes";
    public static final String NO = "no";
    public static final String SHOW = "show";

    private DecisionForms() {}

    /** {@code title} with a yes that says what proceeding does. */
    public static Form confirm(String kind, String title, String yes) {
        return Form.of(kind, oneLine(title), Step.of("decision", "Decision", answer(yes, false)));
    }

    /** {@link #confirm}'s form with the option that opens the dashboard, for a host that can open a link. */
    public static Form confirmOrShow(Form confirm) {
        Field f = confirm.steps().getFirst().fields().getFirst();
        String yes = f.options().getFirst().label();
        return Form.of(
                confirm.id(),
                confirm.title(),
                Step.of("decision", "Decision", answer(yes == null ? "Yes" : yes, true)));
    }

    /** True when {@code answers} settle a confirmation as declined. */
    public static boolean declines(Map<String, Object> answers) {
        return NO.equals(answers.get(ANSWER));
    }

    /** True when {@code answers} ask to see the decision in the dashboard. */
    public static boolean shows(Map<String, Object> answers) {
        return SHOW.equals(answers.get(ANSWER));
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
