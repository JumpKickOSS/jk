// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JobDelta;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two consecutive builds from one MCP session, a failing test fixed between them: the second
 * run's {@code jk-results.md} says what changed since the first — the one file edited, the test
 * that flipped from failed to passed, the failure diagnostic that went away, and the earlier
 * wall — and its journal record carries the same delta for the dashboard and MCP.
 */
// Out of the unit tier: network resolve of JUnit + forked test JVMs.
@Tag("integration")
class IterationDeltaE2eTest {

    private static final String SESSION = "claude-code 3f9a";

    private static final String BROKEN = """
            package com.example;
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            class CalcTest {
                @Test
                void adds() {
                    Assertions.assertEquals(4, 2 + 2);
                }
                @Test
                void subtracts() {
                    Assertions.assertEquals(1, 2 - 2);
                }
            }
            """;

    private static final String FIXED = BROKEN.replace("assertEquals(1, 2 - 2)", "assertEquals(0, 2 - 2)");

    @Test
    void the_second_attempt_reports_what_changed_since_the_first(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("calc"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "calc"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test =
                Files.createDirectories(project.resolve("test/src/com/example")).resolve("CalcTest.java");
        Files.writeString(test, BROKEN);
        Path cache = TestCaches.dir("iteration-delta-cache");
        lock(project, cache);

        BuildJournal journal = new BuildJournal(tmp.resolve("builds"));
        List<String> log = new ArrayList<>();
        JournalWriter writer = new JournalWriter(
                new JobSessions(() -> 0L),
                journal,
                new JkHistoryConfig(true, 30, 512),
                () -> tmp.resolve("metrics.json"),
                System::currentTimeMillis,
                "9.9-test",
                log::add);
        Path results = project.resolve("target/jk-results.md");

        BuildPlanResult first = attempt(writer, 1L, project, cache);
        assertThat(first.success()).as("the first attempt fails on subtracts()").isFalse();
        String firstMd = Files.readString(results, StandardCharsets.UTF_8);
        assertThat(firstMd)
                .as("the first run of a session has nothing to compare against")
                .doesNotContain("## Since the previous run");

        Files.writeString(test, FIXED);
        BuildPlanResult second = attempt(writer, 2L, project, cache);
        assertThat(second.success()).as("the second attempt is green").isTrue();
        assertThat(log).as("journal log: " + log).noneMatch(l -> l.contains("failed") || l.contains("skipped"));

        String md = Files.readString(results, StandardCharsets.UTF_8);
        int at = md.indexOf("## Since the previous run");
        assertThat(at).as("the section is present:\n" + md).isPositive();
        String section = md.substring(at, md.indexOf("## Files"));
        assertThat(section)
                .contains("_vs #1 (failed, ")
                .contains("- Files changed: **1** — `test/src/com/example/CalcTest.java`")
                .contains("- Diagnostics: **0** appeared, **1** gone")
                .contains("  - gone: error · run-tests · ")
                .contains("- Tests: **1** fixed, **0** broke, **0** new, **0** gone")
                .contains("  - fixed: `com.example.CalcTest#subtracts()`");

        List<BuildRecord> records = journal.list();
        assertThat(records).hasSize(2);
        BuildRecord latest = records.get(0);
        assertThat(latest.buildNumber()).isEqualTo(2);
        JobDelta delta = Objects.requireNonNull(latest.delta(), "the second record carries the delta");
        assertThat(delta.previousBuildNumber()).isEqualTo(1);
        assertThat(delta.previousSuccess()).isFalse();
        assertThat(Objects.requireNonNull(delta.fixed()).shown()).containsExactly("com.example.CalcTest#subtracts()");
        assertThat(Objects.requireNonNull(delta.files()).shown()).containsExactly("test/src/com/example/CalcTest.java");
        assertThat(records.get(1).delta())
                .as("the first record has no run before it")
                .isNull();
    }

    /**
     * One build journaled the way the engine journals it: register, bind the request's results
     * sink around the run, fold the plan and tests, write.
     */
    private static BuildPlanResult attempt(JournalWriter writer, long requestId, Path project, Path cache)
            throws Exception {
        writer.register(requestId, "build", project.toString(), "mcp", SESSION, true, false, 0L, null);
        BuildPlan plan = plan(project, cache);
        long start = System.nanoTime();
        BuildPlanResult result;
        writer.openResults(requestId);
        try {
            result = plan.run();
        } finally {
            writer.closeResults();
        }
        long millis = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        writer.accBuildPlanFinish(requestId, "", result);
        writer.accTests(requestId, plan.get(BuildPlanner.TEST_RESULT).orElse(null));
        writer.write(requestId, false, millis, null);
        return result;
    }

    private static void lock(Path project, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();
    }

    private static BuildPlan plan(Path project, Path cache) throws Exception {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                1,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in);
    }
}
