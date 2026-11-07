// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
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
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step worker's {@code notice} diagnostic — what a Quarkus augment says when it leaves an
 * artifact outside the lock out of the application — is a run notice: it reaches the client's
 * stream and the record, and the agent verdict of a build that passed says it. A plain warning
 * stays the step's own and rides the report. A build that restores the step from the action cache
 * says what the run that stored it said, word for word; a packager's diagnostics travel the same
 * way, and so do those of a plugin the lock asks for a closure.
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

    @Test
    void a_build_that_restores_the_step_says_what_its_run_said(@TempDir Path tmp) throws Exception {
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        String notice = PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, DROPPED);
        String warning = PluginReply.diagnostic("warning", "src/main/java/Shop.java", 12, 3, "unused import");

        // The first build runs the step: it says both, and its record keeps them.
        Build ran = new Build();
        ran.run(() -> {
            StepDiagnostics said = ran.step("quarkus-augment");
            said.forward(notice);
            said.forward(warning);
            Files.writeString(scratch.resolve("quarkus-app.txt"), "augmented");
            cache.store("plugin-quarkus-augment", "augment-key", Map.of(), scratch, said.said());
        });
        // The second build finds the same key and restores the step instead of running it.
        Build restored = new Build();
        restored.run(() -> {
            ActionCache.ActionRecord hit = cache.lookup("augment-key").orElseThrow();
            assertThat(cache.restore(hit, scratch)).isTrue();
            restored.step("quarkus-augment").replay(hit);
        });

        assertThat(ran.verdict())
                .contains("\nW shop quarkus-augment: " + DROPPED + "\n")
                .containsOnlyOnce("smallrye-conditional");
        assertThat(restored.verdict()).as("the cache hit's verdict").isEqualTo(ran.verdict());
        assertThat(restored.ctx.warnings)
                .as("the step's own warning, as its run reported it")
                .containsExactly("quarkus-augment=src/main/java/Shop.java:12:3: unused import")
                .isEqualTo(ran.ctx.warnings);
    }

    @Test
    void a_packager_says_what_it_said_when_it_ran_and_again_when_restored(@TempDir Path tmp) throws Exception {
        Path cacheRoot = tmp.resolve("cache");
        Path artifactDir = Files.createDirectories(tmp.resolve("target"));
        Path artifact = Files.writeString(artifactDir.resolve("shop.hlo"), "packed");
        String notice = PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, "no launcher for arm64");

        Build packed = new Build();
        packed.run(() -> {
            StepDiagnostics said = packed.step("hello-pack");
            said.forward(notice);
            PackagingCache.storePackagedForTest(
                    cacheRoot, "package-jar", "pack-key", List.of(), artifactDir, List.of(artifact), true, said.said());
        });
        Files.delete(artifact);
        Build restored = new Build();
        restored.run(() -> assertThat(
                        PackagingCache.restorePackaged(cacheRoot, "pack-key", artifactDir, restored.step("hello-pack")))
                .isTrue());

        assertThat(artifact).hasContent("packed");
        assertThat(packed.verdict()).contains("\nW shop hello-pack: no launcher for arm64\n");
        assertThat(restored.verdict()).isEqualTo(packed.verdict());
    }

    @Test
    void a_plugin_the_lock_asks_for_a_closure_is_heard() {
        BuildAccumulator acc = new BuildAccumulator("lock", MODULE.toString(), "com.example:shop", "cli");
        List<String> lockWarnings = new ArrayList<>();
        LockPipeline.Progress progress = new LockPipeline.Progress() {
            @Override
            public void warn(String code, String message) {
                lockWarnings.add(code + "=" + message);
            }
        };

        inRun(acc, () -> {
            LockClosures.forwardDiagnostic(
                    progress,
                    MODULE,
                    "shop",
                    "quarkus/deployment",
                    PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, DROPPED));
            LockClosures.forwardDiagnostic(
                    progress,
                    MODULE,
                    "shop",
                    "quarkus/deployment",
                    PluginReply.diagnostic("warning", "pom.xml", 4, 0, "relocated artifact"));
            // Outside a plan step the lock has no report: its warning is a run notice instead.
            LockClosures.forwardDiagnostic(
                    LockPipeline.Progress.SILENT,
                    MODULE,
                    "shop",
                    "quarkus/test-deployment",
                    PluginReply.diagnostic("warning", null, 0, 0, "slow mirror"));
        });

        assertThat(lockWarnings).containsExactly("lock quarkus/deployment=pom.xml:4: relocated artifact");
        assertThat(JkResultsAgent.render(acc.toRecord(2_000, false, 900, "0.0.0-test", null)))
                .contains("\nW shop lock quarkus/deployment: " + DROPPED + "\n")
                .contains("\nW lock quarkus/test-deployment: slow mirror\n")
                .doesNotContain("relocated artifact");
    }

    /** A body that may throw, run inside {@link Build#run}. */
    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    /** One build of the module: its own run, record and step report, so two builds compare whole. */
    private static final class Build {
        final BuildAccumulator acc = new BuildAccumulator("build", MODULE.toString(), "com.example:shop", "cli");
        final Recording ctx = new Recording();

        StepDiagnostics step(String name) {
            return new StepDiagnostics(ctx, MODULE, "shop", name);
        }

        void run(Body body) {
            inRun(acc, () -> {
                try {
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        String verdict() {
            return JkResultsAgent.render(acc.toRecord(2_000, false, 900, "0.0.0-test", null));
        }
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
