// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestProgressListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite in one JVM reports each test class as it finishes, not when the JVM exits, so a run
 * stopped part way still records the classes that completed.
 */
class TestClassEventsTest {

    private static final String A = "[engine:junit-jupiter]/[class:demo.A]";
    private static final String B = "[engine:junit-jupiter]/[class:demo.B]";

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_class_is_reported_while_the_suite_jvm_still_runs_and_stays_reported_when_it_is_stopped(@TempDir Path dir)
            throws Exception {
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        List<TestClassResult> reported = new CopyOnWriteArrayList<>();
        TestProgressListener bridge = TestSupport.bridgeListener(recording(reported), 1, false, "g:app");
        JUnitLauncher launcher = new JUnitLauncher().withModuleLabel("g:app");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = pool.submit(() -> {
                try {
                    launcher.run(home, classes, List.of(), cache, 1, Map.of(), bridge);
                } catch (Exception expected) {
                    // the JVM is stopped mid-suite below
                }
                return null;
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (reported.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);

            assertThat(run.isDone()).as("the suite JVM is still running").isFalse();
            assertThat(reported).containsExactly(new TestClassResult("g:app", "demo.A", 3, 1, 1, 40, "", List.of()));

            Files.writeString(dir.resolve("stop"), "");
            run.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reported)
                .as("B started but never finished")
                .containsExactly(new TestClassResult("g:app", "demo.A", 3, 1, 1, 40, "", List.of()));
    }

    @Test
    void a_class_whose_setup_fails_is_reported_as_one_failure() {
        List<TestClassResult> reported = new CopyOnWriteArrayList<>();
        TestProgressListener bridge = TestSupport.bridgeListener(recording(reported), 1, false, "g:app");
        bridge.onTestStarted(A, "A", false, 1);
        bridge.onTestFinished(A, "A", "FAILED", false, true, 5, 1);
        bridge.onTestFinished(A + "/[test-template:t(int)]", "t", "SUCCESSFUL", false, true, 3, 1);

        assertThat(reported).containsExactly(new TestClassResult("g:app", "demo.A", 0, 1, 0, 5, "", List.of()));
    }

    @Test
    void a_parameterized_template_does_not_close_its_class() {
        List<TestClassResult> reported = new CopyOnWriteArrayList<>();
        TestProgressListener bridge = TestSupport.bridgeListener(recording(reported), 1, false, "g:app");
        String template = A + "/[test-template:t(int)]";
        bridge.onTestFinished(template + "/[test-template-invocation:#1]", "[1]", "SUCCESSFUL", true, false, 1, 1);
        bridge.onTestFinished(template, "t", "SUCCESSFUL", false, true, 2, 1);
        assertThat(reported).isEmpty();
        bridge.onTestFinished(B + "/[method:b()]", "b()", "SUCCESSFUL", true, true, 1, 1);
        bridge.onTestFinished(A, "A", "SUCCESSFUL", false, true, 9, 1);

        assertThat(reported).containsExactly(new TestClassResult("g:app", "demo.A", 1, 0, 0, 9, "", List.of()));
    }

    @Test
    void a_class_carries_its_tier_and_its_slowest_methods_longest_first() {
        List<TestClassResult> reported = new CopyOnWriteArrayList<>();
        TestProgressListener bridge =
                TestSupport.bridgeListener(recording(reported), 1, false, "g:app", new TestTier("fast", 0));
        bridge.onTestFinished(A + "/[method:quick()]", "quick()", "SUCCESSFUL", true, true, 12, 1);
        bridge.onTestFinished(A + "/[method:boots(java.nio.file.Path)]", "boots", "SUCCESSFUL", true, true, 1_500, 1);
        String template = A + "/[test-template:each(int)]";
        bridge.onTestFinished(template + "/[test-template-invocation:#2]", "[2]", "SUCCESSFUL", true, false, 4_200, 1);
        bridge.onTestFinished(A, "A", "SUCCESSFUL", false, true, 6_000, 1);

        assertThat(reported)
                .containsExactly(new TestClassResult(
                        "g:app",
                        "demo.A",
                        3,
                        0,
                        0,
                        6_000,
                        "fast",
                        List.of(
                                new TestClassResult.SlowMethod("each[#2]", 4_200),
                                new TestClassResult.SlowMethod("boots", 1_500))));
    }

    @Test
    void a_fast_tier_method_over_the_budget_warns_with_its_name_and_time() {
        List<String> warned = new CopyOnWriteArrayList<>();
        TestProgressListener bridge = TestSupport.bridgeListener(
                recording(new CopyOnWriteArrayList<>(), warned), 1, false, "g:app", new TestTier("fast", 1_000));
        bridge.onTestFinished(A + "/[method:under()]", "under()", "SUCCESSFUL", true, true, 1_000, 1);
        bridge.onTestFinished(A + "/[method:over()]", "over()", "FAILED", true, true, 2_345, 1);

        assertThat(warned)
                .containsExactly("test-budget: demo.A#over took 2.3s, over the fast-tier budget of 1.0s"
                        + " ([test] fast-method-budget)");
    }

    @Test
    void without_a_budget_or_outside_the_fast_tier_nothing_warns() {
        List<String> warned = new CopyOnWriteArrayList<>();
        var ctx = recording(new CopyOnWriteArrayList<>(), warned);
        for (TestTier tier : List.of(
                TestTier.UNKNOWN,
                new TestTier("fast", 0),
                TestTier.of(TestSelection.DEFAULT.withIncludeTags(List.of("integration")), "integration", 1_000))) {
            TestSupport.bridgeListener(ctx, 1, false, "g:app", tier)
                    .onTestFinished(A + "/[method:slow()]", "slow()", "SUCCESSFUL", true, true, 60_000, 1);
        }
        assertThat(warned).isEmpty();
    }

    @Test
    void the_tier_is_fast_without_an_include_tag_else_the_profile_or_its_tags() {
        assertThat(TestTier.name(List.of(), List.of("integration"), false, null))
                .isEqualTo("fast");
        assertThat(TestTier.name(List.of(), List.of("integration"), false, "ci"))
                .isEqualTo("fast");
        assertThat(TestTier.name(List.of(), List.of(), true, null)).isEqualTo("all");
        assertThat(TestTier.name(List.of("integration"), List.of("slow"), false, "integration"))
                .isEqualTo("integration");
        assertThat(TestTier.name(List.of("slow", "network"), List.of(), false, null))
                .isEqualTo("slow+network");
        assertThat(TestTier.of(TestSelection.DEFAULT, null, 750).budgetMs()).isEqualTo(750);
    }

    private static TaskContext recording(List<TestClassResult> into) {
        return recording(into, new CopyOnWriteArrayList<>());
    }

    private static TaskContext recording(List<TestClassResult> into, List<String> warned) {
        return new TaskContext() {
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
                warned.add(code + ": " + message);
            }

            @Override
            public void error(String code, String message) {}

            @Override
            public void testClass(TestClassResult result) {
                into.add(result);
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
                throw new IllegalStateException(key.toString());
            }
        };
    }

    /**
     * Class A: two passing tests (one aborted counts as skipped), one failure; then B starts, and
     * the JVM holds until the test writes {@code stop}, then exits mid-suite.
     */
    private static Path fakeJdk(Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path home = dir.resolve("jdk");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path java = bin.resolve("java");
        String a = A;
        String b = B;
        String script = """
                #!/bin/sh
                e() { printf '%s\\n' "##JKT:$1"; }
                e '{"event":"started","type":"CONTAINER","uniqueId":"@A@"}'
                e '{"event":"finished","type":"TEST","status":"SUCCESSFUL","uniqueId":"@A@/[method:a1()]","testClass":"demo.A","testMethod":"a1()","duration_ms":1}'
                e '{"event":"finished","type":"TEST","status":"FAILED","uniqueId":"@A@/[method:a2()]","testClass":"demo.A","testMethod":"a2()","duration_ms":1,"throwable":{"class":"java.lang.AssertionError","message":"no","stack":""}}'
                e '{"event":"finished","type":"TEST","status":"ABORTED","uniqueId":"@A@/[method:a3()]","testClass":"demo.A","testMethod":"a3()","duration_ms":1}'
                e '{"event":"finished","type":"CONTAINER","status":"SUCCESSFUL","uniqueId":"@A@","duration_ms":40}'
                e '{"event":"started","type":"CONTAINER","uniqueId":"@B@"}'
                e '{"event":"finished","type":"TEST","status":"SUCCESSFUL","uniqueId":"@B@/[method:b1()]","testClass":"demo.B","testMethod":"b1()","duration_ms":1}'
                while [ ! -f "STOP" ]; do sleep 0.05; done
                exit 1
                """.replace("@A@", a)
                .replace("@B@", b)
                .replace("STOP", dir.resolve("stop").toString());
        Files.writeString(java, script);
        Files.setPosixFilePermissions(
                java,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return home;
    }
}
