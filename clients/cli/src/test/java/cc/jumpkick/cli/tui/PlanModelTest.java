// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The plan state machine driven directly: no terminal, no JkManager, no threads. */
class PlanModelTest {

    /** Records what the region would be told; nothing here paints. */
    private static final class Recording implements PlanModel.Events {
        final List<String> log = new ArrayList<>();

        @Override
        public void stepStarted(String module) {
            log.add("started " + module);
        }

        @Override
        public void stepMessage(PlanModel.Row row) {
            log.add("message " + row.message);
        }

        @Override
        public void testTick(PlanModel.Row row, int delta) {
            log.add("tick " + delta);
        }

        @Override
        public void moduleBuilt(String module) {
            log.add("built " + module);
        }

        @Override
        public void preflight(String pill, int done, int total, String label) {
            log.add("preflight " + pill + " " + done + "/" + total);
        }
    }

    @Test
    void a_late_step_start_never_resurrects_a_finished_row() {
        Recording events = new Recording();
        PlanModel model = new PlanModel(new Object(), events);
        model.stepRunning("g:a", "compile-java", "compile");
        model.stepDone("g:a", "compile-java", true, "compile");
        PlanModel.Row row = model.rows().values().iterator().next();
        assertThat(row.state).isEqualTo(PlanModel.RowState.DONE);

        model.stepRunning("g:a", "compile-java", "compile");
        model.stepDone("g:a", "compile-java", false, "compile");

        assertThat(row.state).as("terminal state is monotonic").isEqualTo(PlanModel.RowState.DONE);
        assertThat(events.log).containsExactly("started g:a");
    }

    @Test
    void a_phase_leaves_the_chain_when_its_last_step_succeeds_and_stays_red_when_one_failed() {
        PlanModel model = new PlanModel(new Object(), new Recording());
        model.stepRunning("g:a", "compile-java", "compile");
        model.stepRunning("g:b", "compile-java", "compile");
        assertThat(model.phaseOrder()).containsExactly("compile");
        assertThat(phase(model, "compile").runningCount).isEqualTo(2);

        model.stepDone("g:a", "compile-java", true, "compile");
        assertThat(phase(model, "compile").state).isEqualTo(PlanModel.PhaseState.RUNNING);
        model.stepDone("g:b", "compile-java", true, "compile");
        assertThat(model.phases())
                .as("all clean: the phase is gone from the live chain")
                .isEmpty();
        assertThat(model.phaseOrder()).isEmpty();

        model.stepRunning("g:c", "run-tests", "test");
        model.stepDone("g:c", "run-tests", false, "test");
        PlanModel.PhaseNode test = phase(model, "test");
        assertThat(test.state).isEqualTo(PlanModel.PhaseState.FAILED);
        assertThat(test.briefError).isEqualTo("Failed");
        assertThat(model.phaseOrder()).containsExactly("test");
    }

    @Test
    void a_brief_error_is_cut_at_96_chars_without_splitting_a_surrogate_pair() {
        PlanModel model = new PlanModel(new Object(), new Recording());
        model.stepRunning("g:a", "run-tests", "test");
        model.stepDone("g:a", "run-tests", false, "test");

        String plain = "x".repeat(200);
        model.attachPhaseError("g:a", "run-tests", "test", plain);
        String cut = phase(model, "test").briefError;
        assertThat(cut).hasSize(93 + 1).endsWith("…");

        // 92 chars then an emoji whose high surrogate sits at index 92: the cut steps back one.
        String emoji = "y".repeat(92) + "😀" + "z".repeat(50);
        model.attachPhaseError("g:a", "run-tests", "test", emoji);
        String safe = phase(model, "test").briefError;
        assertThat(safe).endsWith("…");
        assertThat(Character.isHighSurrogate(safe.charAt(safe.length() - 2)))
                .as("never a lone high surrogate before the ellipsis")
                .isFalse();
        assertThat(safe).hasSize(92 + 1);
    }

    @Test
    // The null label is deliberate: a preflight stage with no header text.
    @SuppressWarnings("NullAway")
    void the_region_hears_each_change_inside_the_same_critical_section() {
        Recording events = new Recording();
        PlanModel model = new PlanModel(new Object(), events);
        model.stepRunning("g:a", "compile-java", "compile");
        model.stepMessage("g:a", "compile-java", "12 sources");
        model.finishModule("g:a", true);
        model.preflight("checking", 1, 4, null);
        assertThat(events.log)
                .containsExactly("started g:a", "message 12 sources", "built g:a", "preflight Checking 1/4");
    }

    private static PlanModel.PhaseNode phase(PlanModel model, String name) {
        assertThat(model.phases()).containsKey(name);
        return Objects.requireNonNull(model.phases().get(name));
    }
}
