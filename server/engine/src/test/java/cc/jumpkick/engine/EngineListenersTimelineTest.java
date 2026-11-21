// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workspace request writes one timeline, of the whole run, not of the first module to finish. */
class EngineListenersTimelineTest {

    private static final long RID = 1L;

    @TempDir
    Path tmp;

    @Test
    void every_module_of_a_workspace_is_in_its_timeline() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        EngineListeners listeners = listeners(ws);
        WorkspaceBuildListener workspace = listeners.workspace(null, ws.toString());

        for (String module : List.of("lib", "app")) {
            BuildPlanListener plan = workspace.onModuleStart(new ModulePlan(
                    ws.resolve(module), "g:" + module, BuildPlan.builder(module).build(), 1, false, tmp));
            plan.stepFinish("compile-java", "compile", TaskStatus.SUCCESS, Duration.ofMillis(5), Duration.ZERO);
            plan.planFinish(new BuildPlanResult(
                    module,
                    true,
                    Duration.ofMillis(5),
                    List.of(new BuildPlanResult.StepReport("compile-java", TaskStatus.SUCCESS, Duration.ofMillis(5))),
                    List.of(),
                    List.of(),
                    false));
        }
        listeners.flushTimeline(RID, null, true);

        assertThat(Files.readString(ws.resolve("target/jk-profile.json")))
                .contains("\"cat\":\"lib\"")
                .contains("\"cat\":\"app\"");
    }

    private EngineListeners listeners(Path ws) {
        JobSessions sessions = new JobSessions(() -> RID);
        JournalWriter journal = new JournalWriter(
                sessions,
                new BuildJournal(tmp.resolve("builds")),
                new JkHistoryConfig(false, 30, 512),
                () -> BuildMetrics.load(tmp.resolve("metrics.json")),
                () -> 0L,
                "9.9",
                s -> {});
        journal.register(RID, "build", ws.toString(), "cli", null, false, false, 0, null);
        InFlightBuilds inFlight = new InFlightBuilds();
        SsePublisher sse = new SsePublisher(
                sessions,
                inFlight,
                null,
                () -> null,
                () -> 0L,
                new AtomicInteger(),
                new ReentrantReadWriteLock(),
                (rid, dir, step, phase) -> {});
        return new EngineListeners(sessions, sse, journal, inFlight, () -> RID);
    }
}
