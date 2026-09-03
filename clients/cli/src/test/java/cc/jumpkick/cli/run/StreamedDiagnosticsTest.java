// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One compile error is two wire records — the live error line and the plan-finish summary — and
 * a listener that renders the first must not render the second. The live form is the richer one
 * (it knows the module, so its header reads {@code Compile Java Failure in g:app}); the summary
 * exists for listeners that only ever see the end of the plan.
 */
class StreamedDiagnosticsTest {

    private static final String MESSAGE = "App.java:3: error: cannot find symbol zorblax";

    private static BuildPlanResult failed(BuildPlanResult.Diagnostic... errors) {
        return new BuildPlanResult("build", false, Duration.ZERO, List.of(), List.of(), List.of(errors), false, false);
    }

    private static long blocks(ByteArrayOutputStream buf) {
        return TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))
                .lines()
                .filter(l -> l.contains("zorblax"))
                .count();
    }

    @Test
    void an_error_streamed_live_is_not_rendered_again_at_finish() {
        var buf = new ByteArrayOutputStream();
        var spec = new ConsoleSpec("Build", r -> "ok", r -> "failed", true);
        var lis = new CommandManagerListener(
                new PrintStream(buf, true, StandardCharsets.UTF_8), spec, "g:app", List.of(), false);
        lis.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        lis.error("compile-java", "javac", MESSAGE);
        lis.planFinish(failed(new BuildPlanResult.Diagnostic("compile-java", "javac", MESSAGE)));

        assertThat(blocks(buf)).as("the error body prints once").isEqualTo(1);
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8)))
                .as("the one block is the live form, which knows the module")
                .contains("g:app");
    }

    /** A listener that receives only the summary still renders the error. */
    @Test
    void a_summary_only_finish_still_renders_the_error() {
        var buf = new ByteArrayOutputStream();
        var spec = new ConsoleSpec("Build", r -> "ok", r -> "failed", true);
        var lis = new CommandManagerListener(
                new PrintStream(buf, true, StandardCharsets.UTF_8), spec, "g:app", List.of(), false);
        lis.planStart(new BuildPlanView("build", 0, 10, 1, 0, false));
        lis.planFinish(failed(new BuildPlanResult.Diagnostic("compile-java", "javac", MESSAGE)));

        assertThat(blocks(buf)).isEqualTo(1);
    }

    /** The workspace settle renders only what no module listener already streamed. */
    @Test
    void the_workspace_settle_skips_diagnostics_a_module_streamed() {
        JkManager view = JkManager.plan(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        var agg = new AggregateContext(view);
        var lis = new AggregateModuleListener(agg, "g:app", List.of());
        lis.error("compile-java", "javac", MESSAGE);
        var streamed = new BuildPlanResult.Diagnostic("compile-java", "javac", MESSAGE);
        var summaryOnly = new BuildPlanResult.Diagnostic("resolve-deps", "resolver", "no such artifact");
        lis.planFinish(failed(streamed, summaryOnly));

        assertThat(agg.lastErrors()).as("the transcript still sees every error").hasSize(2);
        assertThat(agg.unstreamedErrors()).containsExactly(summaryOnly);
        view.close();
    }
}
