// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A step worker's {@code notice} diagnostic — what a Quarkus augment says when it leaves an
 * artifact outside the lock out of the application — is a run notice: it reaches the client's
 * stream and the record, and the agent verdict of a build that passed says it. A plain warning
 * stays the step's own and rides the report.
 */
class StepNoticeTest {

    private static final Path MODULE = Path.of("/ws/shop");
    private static final String DROPPED = "NOT shipped — outside jk-lock.toml (declare them under [dependencies]"
            + " and re-lock to ship them): io.smallrye:smallrye-conditional:2.1.0";

    @BeforeEach
    @AfterEach
    void forgetRunNotices() {
        RunNotices.clear();
    }

    @Test
    void a_notice_from_a_step_reaches_a_passing_builds_verdict_and_report() {
        BuildAccumulator acc = new BuildAccumulator("build", MODULE.toString(), "com.example:shop", "cli");
        acc.addTask(MODULE.toString(), "plugin-quarkus-augment", "build", "SUCCESS", 900, 0);
        Recording ctx = new Recording();

        inRun(acc, () -> {
            String notice = PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, DROPPED);
            PlannerPlugin.forwardStepDiagnostic(ctx, MODULE, "shop", "quarkus-augment", notice);
            // A step that runs twice in one run (the augment and a re-run after a retry) says it once.
            PlannerPlugin.forwardStepDiagnostic(ctx, MODULE, "shop", "quarkus-augment", notice);
        });

        assertThat(ctx.warnings).as("a notice is not also the step's warning").isEmpty();
        BuildRecord record = acc.toRecord(2_000, false, 900, "0.0.0-test", null);
        assertThat(record.success()).isTrue();
        assertThat(JkResultsAgent.render(record))
                .startsWith("OK build shop")
                .contains("\nW shop quarkus-augment: " + DROPPED + "\n")
                .containsOnlyOnce("smallrye-conditional");
        assertThat(JkResultsMarkdown.render(record)).contains("## Warnings").contains("smallrye-conditional");
    }

    @Test
    void a_warning_from_a_step_stays_the_steps_own() {
        BuildAccumulator acc = new BuildAccumulator("build", MODULE.toString(), "com.example:shop", "cli");
        Recording ctx = new Recording();

        inRun(
                acc,
                () -> PlannerPlugin.forwardStepDiagnostic(
                        ctx,
                        MODULE,
                        "shop",
                        "lint",
                        PluginReply.diagnostic("warning", "src/main/java/Shop.java", 12, 3, "unused import")));

        assertThat(ctx.warnings).containsExactly("lint=src/main/java/Shop.java:12:3: unused import");
        assertThat(acc.toRecord(2_000, false, 900, "0.0.0-test", null).diagnostics())
                .as("no run notice")
                .noneMatch(d -> RunNotices.CODE.equals(d.code()));
    }

    /**
     * {@code body} inside a run whose notices land on {@code acc}, as the engine's job envelope
     * routes them: the run's ledger is ambient, and its sink adds each note to the run's record.
     */
    private static void inRun(BuildAccumulator acc, Runnable body) {
        IoLedger run = new IoLedger();
        IoLedger.open(run);
        RunNotices.openSink(run, (code, message) -> acc.addNotice(RunNotices.CODE, message));
        try {
            SessionContext.runWhere(Session.defaults(), body);
        } finally {
            RunNotices.closeSink(run);
            IoLedger.close();
        }
    }

    /** The step's report entries; everything else is inert. */
    private static final class Recording implements TaskContext {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {
            warnings.add(code + "=" + message);
        }

        @Override
        public void error(String code, String message) {
            warnings.add("error " + code + "=" + message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
