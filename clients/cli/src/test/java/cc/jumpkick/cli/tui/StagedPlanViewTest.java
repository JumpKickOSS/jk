// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static cc.jumpkick.cli.tui.JkManagerTestSupport.stripAll;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.CommandManagerListener;
import cc.jumpkick.cli.run.StageLines;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** A plan whose steps are its stages ({@code jk import}) names each stage on its row and keeps a line per finished stage. */
class StagedPlanViewTest {

    private static final List<Task> STEPS = List.of(
            step("import-read", "Evaluate with Gradle"),
            step("import-write", "Write manifests"),
            step("import-check-pins", "Check pins"),
            step("import-report", "Report"));

    @Test
    void the_running_stage_names_its_row_and_a_finished_stage_leaves_its_line() {
        CommandManagerListener listener =
                CommandManagerListener.staged(stream(new ByteArrayOutputStream()), "Import", "shop", STEPS, false);
        listener.planStart(new BuildPlanView("import", 0, 100, 4, 0, false));
        JkManager cm = (JkManager) Objects.requireNonNull(LiveRegion.active());
        cm.nerdFont = NerdFontCaps.NONE;

        listener.stepStart("import-read", "other", 1);
        listener.label("import-read", "resolved :core (12/51)");
        String reading = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 65_000)));
        assertThat(reading).contains("Evaluate with Gradle").contains("resolved :core (12/51)");
        assertThat(reading).doesNotContain("Other");

        listener.label("import-read", "resolved :app (51/51)");
        listener.stepFinish("import-read", "other", TaskStatus.SUCCESS, Duration.ofSeconds(130), Duration.ZERO);
        listener.stepStart("import-write", "other", 1);
        listener.label("import-write", "core/jk.toml");
        String writing = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 131_000)));
        assertThat(writing)
                .contains("Write manifests")
                .contains("core/jk.toml")
                .contains("Evaluate with Gradle · resolved :app (51/51)");
        cm.dismiss();
    }

    @Test
    void without_a_live_view_each_stage_prints_its_line_as_it_ends() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BuildPlanListener printing = StageLines.printing(stream(buf), STEPS);

        printing.label("import-check-pins", "Gradle resolved every pin");
        printing.stepFinish("import-check-pins", "other", TaskStatus.SKIPPED, Duration.ofMillis(3), Duration.ZERO);
        printing.stepFinish("import-report", "other", TaskStatus.FAIL, Duration.ofMillis(5), Duration.ZERO);

        List<String> lines =
                stripAll(List.of(buf.toString(StandardCharsets.UTF_8).split("\n")));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .contains("Check pins · Gradle resolved every pin")
                .contains("took");
        assertThat(lines.get(1)).contains("Report").doesNotContain("·");
    }

    private static Task step(String name, String label) {
        return Task.builder(name).label(label).stage(BuildStage.OTHER).build();
    }
}
