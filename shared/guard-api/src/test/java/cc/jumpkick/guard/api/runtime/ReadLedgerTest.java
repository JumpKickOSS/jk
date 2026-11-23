// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** A guard whose reads all hold replays its last line; an edit to anything it read runs it again. */
class ReadLedgerTest {

    static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    private static void ran(String id) {
        RUNS.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
    }

    @Disabled("run by the tests below through the launcher, with the condition deactivated")
    @GuardSuite(scope = Scope.WORKSPACE)
    static class Suite {
        @Guard(id = "reads-a", why = "w")
        void readsA(Text text, Violations v) {
            ran("reads-a");
            List<String> lines = text.lines("src/a.txt");
            for (int i = 0; i < lines.size(); i++)
                if (lines.get(i).contains("bad")) v.add(new TextSite("src/a.txt", i + 1, "bad"), "a bad line");
            v.population(lines.size());
        }

        @Guard(id = "lists-docs", why = "w")
        void listsDocs(Text text, Violations v) {
            ran("lists-docs");
            v.population(text.files("src/**/*.md").size());
        }

        @Guard(id = "reads-model", why = "w")
        void readsModel(Model model, Violations v) {
            ran("reads-model");
            v.population(model.modules().size());
        }

        @Guard(id = "reads-facts", why = "w")
        void readsFacts(Facts facts, Violations v) {
            ran("reads-facts");
            v.population(facts.classes().size());
        }

        @Guard(id = "throws", why = "w")
        void throwsUp(Text text, Violations v) {
            ran("throws");
            text.lines("src/a.txt");
            throw new IllegalStateException("kaboom");
        }
    }

    @TempDir
    Path root;

    private Path report;

    @BeforeEach
    void tree() throws Exception {
        RUNS.clear();
        Files.createDirectories(root.resolve("src/docs"));
        Files.writeString(root.resolve("src/a.txt"), "fine\nbad\n");
        Files.writeString(root.resolve("src/docs/one.md"), "# one\n");
        Files.writeString(root.resolve("model.json"), "{\"modules\":[\"\",\"lib\"]}");
        report = root.resolve("target/guard/report.jsonl");
    }

    /** One run as the engine forks it: a fresh runtime, the report emptied first. */
    private Map<String, Object> run(boolean fixture) throws Exception {
        Files.deleteIfExists(report);
        GuardRuntime.install(new GuardConfig(
                report,
                root,
                "",
                List.of(),
                List.of(),
                root.resolve("model.json"),
                List.of(root.resolve("src")),
                List.of(),
                List.of(),
                List.of(),
                null,
                fixture,
                null));
        Launcher launcher = LauncherFactory.create();
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Suite.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        launcher.execute(req, summary);
        assertThat(summary.getSummary().getTestsFailedCount()).isZero();
        Map<String, Object> byId = new TreeMap<>();
        for (String l : Files.readAllLines(report)) byId.put(MiniJson.str(MiniJson.parse(l), "id"), MiniJson.parse(l));
        assertThat(byId).as("every guard reports, run or replayed").hasSize(5);
        return byId;
    }

    private Map<String, Object> run() throws Exception {
        return run(false);
    }

    private static int runs(String id) {
        AtomicInteger n = RUNS.get(id);
        return n == null ? 0 : n.get();
    }

    private static boolean replayed(Map<String, Object> byId, String id) {
        return Boolean.TRUE.equals(MiniJson.get(byId.get(id), "replayed"));
    }

    @Test
    void a_guard_whose_reads_hold_replays_its_line_and_the_rest_run() throws Exception {
        Map<String, Object> first = run();
        assertThat(replayed(first, "reads-a")).isFalse();

        Map<String, Object> second = run();

        assertThat(runs("reads-a")).isEqualTo(1);
        assertThat(runs("lists-docs")).isEqualTo(1);
        assertThat(runs("reads-model")).isEqualTo(1);
        assertThat(replayed(second, "reads-a")).isTrue();
        assertThat(MiniJson.get(second.get("reads-a"), "violations"))
                .as("the replayed line carries the verdict of the run it repeats")
                .isEqualTo(MiniJson.get(first.get("reads-a"), "violations"));
        assertThat(runs("reads-facts")).as("a guard taking Facts always runs").isEqualTo(2);
        assertThat(runs("throws"))
                .as("a guard that threw is no verdict to repeat")
                .isEqualTo(2);
        assertThat(MiniJson.str(second.get("throws"), "outcome")).isEqualTo("threw");

        Map<String, Object> third = run();
        assertThat(runs("reads-a")).as("a replayed guard replays again").isEqualTo(1);
        assertThat(replayed(third, "reads-a")).isTrue();
    }

    @Test
    void an_edit_to_a_file_a_guard_read_runs_that_guard_only() throws Exception {
        run();
        Files.writeString(root.resolve("src/a.txt"), "fine\nbad\nbad again\n");

        Map<String, Object> after = run();

        assertThat(runs("reads-a")).isEqualTo(2);
        assertThat(replayed(after, "reads-a")).isFalse();
        assertThat((List<?>) MiniJson.get(after.get("reads-a"), "violations")).hasSize(2);
        assertThat(runs("lists-docs")).isEqualTo(1);
        assertThat(runs("reads-model")).isEqualTo(1);
    }

    @Test
    void a_file_added_under_a_listed_glob_runs_the_guard_that_listed_it() throws Exception {
        run();
        Files.writeString(root.resolve("src/docs/two.md"), "# two\n");

        Map<String, Object> after = run();

        assertThat(runs("lists-docs")).isEqualTo(2);
        assertThat(MiniJson.get(after.get("lists-docs"), "population")).isEqualTo(2L);
        assertThat(runs("reads-a")).isEqualTo(1);
    }

    @Test
    void a_changed_model_runs_the_guards_that_take_it() throws Exception {
        run();
        Files.writeString(root.resolve("model.json"), "{\"modules\":[\"\",\"lib\",\"app\"]}");

        run();

        assertThat(runs("reads-model")).isEqualTo(2);
        assertThat(runs("reads-a")).isEqualTo(1);
    }

    @Test
    void a_fixture_run_replays_nothing() throws Exception {
        run(true);
        run(true);

        assertThat(runs("reads-a")).isEqualTo(2);
        assertThat(runs("lists-docs")).isEqualTo(2);
    }

    @Test
    void the_stamp_moves_with_the_suite_s_classes_and_not_with_the_compile_s_stamp() throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes/a"));
        Files.writeString(classes.resolve("Rules.class"), "v1");
        Files.writeString(root.resolve("classes").resolve(BuildStamps.JAVA), "STAMP_MILLIS 1\n");
        Path jar = root.resolve("lib.jar");
        Files.writeString(jar, "jar v1");
        List<Path> cp = List.of(root.resolve("classes"), jar);
        String before = ReadLedger.stamp(cp, "scope");

        Files.writeString(root.resolve("classes").resolve(BuildStamps.JAVA), "STAMP_MILLIS 2\n");
        assertThat(ReadLedger.stamp(cp, "scope"))
                .as("the compile's clock is no code")
                .isEqualTo(before);

        Files.writeString(classes.resolve("Rules.class"), "v2");
        String ruleEdited = ReadLedger.stamp(cp, "scope");
        assertThat(ruleEdited).as("a rule edit").isNotEqualTo(before);

        Files.writeString(jar, "jar v2");
        assertThat(ReadLedger.stamp(cp, "scope")).as("a library edit").isNotEqualTo(ruleEdited);
        assertThat(ReadLedger.stamp(cp, "other scope")).isNotEqualTo(ReadLedger.stamp(cp, "scope"));
    }
}
