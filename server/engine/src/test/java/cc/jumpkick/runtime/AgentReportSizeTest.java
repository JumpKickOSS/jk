// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.util.MarkdownReports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Byte sizes of the human report and the agent report for one real project: a green build, a
 * missing semicolon, a failing test, and a dependency taken back out.
 */
@Tag("integration")
class AgentReportSizeTest {

    @TempDir
    Path tmp;

    @Test
    void report_sizes_for_a_real_project() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("size-app"));
        Path src = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Path testSrc = Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(src.resolve("App.java"), app(true));
        Files.writeString(project.resolve("jk.toml"), manifest(false));
        Path cache = TestCaches.dir("agent-report-size");
        lock(project, cache);

        JournalWriter writer = writer();
        BuildPlanResult ok = attempt(writer, 1, project, cache, "build");
        assertThat(ok.success()).isTrue();
        assertThat(sizes("ok", project)).isLessThanOrEqualTo(120);

        Files.writeString(src.resolve("App.java"), app(false));
        BuildPlanResult broken = attempt(writer, 2, project, cache, "build");
        assertThat(broken.success()).isFalse();
        int compile = sizes("compile", project);
        assertThat(compile).isLessThanOrEqualTo(600);

        Files.writeString(src.resolve("App.java"), app(true));
        Files.writeString(testSrc.resolve("AppTest.java"), test(false));
        Files.writeString(project.resolve("jk.toml"), manifest(true));
        lock(project, cache);
        BuildPlanResult red = attempt(writer, 3, project, cache, "test");
        assertThat(red.success()).isFalse();
        sizes("test", project);

        Files.deleteIfExists(testSrc.resolve("AppTest.java"));
        Files.writeString(src.resolve("App.java"), missingImports());
        BuildPlanResult missing = attempt(writer, 4, project, cache, "build");
        assertThat(missing.success()).isFalse();
        sizes("missing-dep", project);
    }

    /** Prints one row and returns the agent report's byte size. */
    private static int sizes(String label, Path project) throws Exception {
        String md = MarkdownReports.strip(
                Files.readString(project.resolve("target/jk-results.md"), StandardCharsets.UTF_8));
        String agent = Files.readString(project.resolve("target/jk-agent.txt"), StandardCharsets.UTF_8);
        int mdBytes = md.getBytes(StandardCharsets.UTF_8).length;
        int agentBytes = agent.getBytes(StandardCharsets.UTF_8).length;
        System.out.println("SIZE " + label + " human=" + mdBytes + " agent=" + agentBytes);
        System.out.println("--- agent " + label + " ---");
        System.out.println(agent);
        assertThat(agentBytes).isLessThan(mdBytes);
        return agentBytes;
    }

    private JournalWriter writer() {
        return new JournalWriter(
                new JobSessions(() -> 0L),
                new BuildJournal(tmp.resolve("builds")),
                new JkHistoryConfig(true, 30, 512),
                () -> tmp.resolve("metrics.json"),
                System::currentTimeMillis,
                "0.14.0",
                line -> {});
    }

    private static void lock(Path project, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    private static BuildPlanResult attempt(JournalWriter writer, long requestId, Path project, Path cache, String kind)
            throws Exception {
        writer.register(requestId, kind, project.toString(), "cli", null, true, false, 0L, null);
        boolean test = "test".equals(kind);
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
                test,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlan plan = BuildPlanner.fullPlan(in);
        long start = System.nanoTime();
        writer.openResults(requestId);
        BuildPlanResult result;
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

    private static String manifest(boolean junit) {
        String deps = junit ? """

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """ : """

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """;
        return """
                name    = "size-app"
                group   = "com.example"
                version = "0.1.0"
                java    = 25
                """ + deps;
    }

    private static String app(boolean semicolon) {
        String end = semicolon ? ";" : "";
        return """
                package com.example;

                public final class App {
                    public static void main(String[] args) {
                        System.out.println("hi")%s
                    }
                }
                """.formatted(end);
    }

    /** Four imports of one package that is not on the classpath. */
    private static String missingImports() {
        return """
                package com.example;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.PathVariable;

                public final class App {}
                """;
    }

    private static String test(boolean passes) {
        String expected = passes ? "hi" : "bye";
        return """
                package com.example;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class AppTest {
                    @Test
                    void greeting() {
                        assertEquals("%s", "hi");
                    }
                }
                """.formatted(expected);
    }
}
