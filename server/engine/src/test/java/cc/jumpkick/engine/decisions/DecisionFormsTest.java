// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionFormsTest {

    private final Form batch = DecisionForms.batch(
            "setup",
            "Set up this machine",
            List.of(
                    new DecisionForms.Item(
                            "exclude-1", "Exclude C:\\src", "Holds projects. Builds stop paying for the scan."),
                    new DecisionForms.Item("long-paths", "Long paths", "Windows stops at 260.")),
            "Apply");

    @Test
    void aBatchIsOneTickedCheckboxPerChangeThenTheAnswer() {
        List<Field> fields = batch.steps().getFirst().fields();
        Field changes = fields.getFirst();
        assertThat(changes.key()).isEqualTo(DecisionForms.CHANGES);
        assertThat(changes.required()).isTrue();
        assertThat(changes.options()).extracting(o -> o.label()).containsExactly("Exclude C:\\src", "Long paths");
        assertThat(changes.options().getFirst().help()).isEqualTo("Holds projects. Builds stop paying for the scan.");
        assertThat(Form.defaultOf(changes, DerivedDefaults.NONE)).isEqualTo(List.of("exclude-1", "long-paths"));
        assertThat(fields.get(1).key()).isEqualTo(DecisionForms.ANSWER);
    }

    @Test
    void anEmptyBatchIsAPlainConfirmation() {
        assertThat(DecisionForms.batch("k", "Q", List.of(), "Yes")
                        .steps()
                        .getFirst()
                        .fields())
                .extracting(Field::key)
                .containsExactly(DecisionForms.ANSWER);
    }

    @Test
    void showIsAddedToTheAnswerAndTheChecklistStays() {
        List<Field> fields =
                DecisionForms.confirmOrShow(batch).steps().getFirst().fields();
        assertThat(fields).extracting(Field::key).containsExactly(DecisionForms.CHANGES, DecisionForms.ANSWER);
        assertThat(fields.get(1).options()).extracting(o -> o.id()).contains(DecisionForms.SHOW);
        assertThat(fields.get(1).options().getFirst().label()).isEqualTo("Apply");
    }

    @Test
    void chosenReadsTheTickedChanges() {
        assertThat(DecisionForms.chosen(Map.of(DecisionForms.CHANGES, List.of("long-paths"), "answer", "yes")))
                .containsExactly("long-paths");
        assertThat(DecisionForms.chosen(Map.of("answer", "yes"))).isEmpty();
    }
}
