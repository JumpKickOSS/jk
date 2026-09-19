// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.eval.Evaluation;
import cc.jumpkick.guard.eval.Evaluators;
import cc.jumpkick.guard.eval.GuardThrash;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.base.TestStoreSeed;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lane end to end: the module lane reads the facts index the build wrote, a violation is a
 * diagnostic whose code is the rule id, freezing it turns the next build green, and the build after
 * that is a verdict hit.
 */
class GuardLaneE2eTest {

    @AfterEach
    void unregister() {
        // The registry is static and the fork is shared: a stub left here makes every later forbid
        // evaluation in this JVM `unsupported`, and the fixture proofs of another class go silent.
        Evaluators.restoreDefaults();
    }

    @Test
    void module_lane_reports_freezes_and_caches(@TempDir Path tmp) throws Exception {
        Path project = scaffold(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        // A stand-in for the forbid evaluator: every class in the facts index is a "site".
        Evaluators.register(Kind.FORBID, (rule, ctx) -> {
            List<Observation> sites = new ArrayList<>();
            for (ClassFacts c : ctx.facts().classList()) {
                sites.add(Observation.site(
                        c.binaryName() + " -> banned", c.sourceFile(), 1, c.binaryName() + " calls the banned thing"));
            }
            return Evaluation.of(Map.of("classes", (long) ctx.facts().classes().size()), sites);
        });

        GuardThrash.reset();
        BuildPlanResult first = build(project, cache);
        assertThat(first.success()).as("errors: " + first.errors()).isFalse();
        assertThat(first.errors()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("no-app");
            assertThat(d.message())
                    .startsWith("App.java:1: demo.App calls the banned thing")
                    .contains("Instead:  nothing")
                    .contains("Baseline: new")
                    .contains("Exempt:   ask the user")
                    .doesNotContain("Thrash:");
        });

        // The same site red again in the same engine session: the message turns to stop-and-ask.
        BuildPlanResult again = build(project, cache);
        assertThat(again.success()).isFalse();
        assertThat(again.errors()).anySatisfy(d -> assertThat(d.message())
                .contains("Thrash:   this site has failed on 2 consecutive builds — stop and ask the user")
                .doesNotContain("Exempt:"));
        // A restart forgets the streak.
        GuardThrash.reset();
        BuildPlanResult afterRestart = build(project, cache);
        assertThat(afterRestart.errors())
                .anySatisfy(d -> assertThat(d.message()).contains("Exempt:").doesNotContain("Thrash:"));
        assertThat(project.resolve("target/incremental/main-guard.idx"))
                .as("facts index written by the lane")
                .exists();
        assertThat(status(first, TaskNames.GUARD)).isEqualTo(TaskStatus.FAIL);
        assertThat(status(first, TaskNames.GUARD_MODEL))
                .as("no model rules: ran, nothing to say")
                .isEqualTo(TaskStatus.SUCCESS);

        // Freeze the site with a reason, as `jk guard freeze` would.
        Baseline frozen = Baseline.EMPTY.with(
                "no-app",
                RuleBaseline.of(Map.of("classes", 1L), List.of(new Entry.Site("demo.App -> banned", "agreed"))));
        BaselineFile.write(GuardsPresence.baselineFile(project), frozen);

        BuildPlanResult second = build(project, cache);
        assertThat(second.errors()).isEmpty();
        assertThat(second.success()).isTrue();
        assertThat(status(second, TaskNames.GUARD)).isEqualTo(TaskStatus.SUCCESS);

        BuildPlanResult third = build(project, cache);
        assertThat(third.success()).isTrue();
        assertThat(status(third, TaskNames.GUARD))
                .as("verdict hit on unchanged facts, rules and baseline")
                .isEqualTo(TaskStatus.SKIPPED);
    }

    @Test
    void model_lane_fails_when_the_baseline_names_a_rule_source_does_not_declare(@TempDir Path tmp) throws Exception {
        Path project = scaffold(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        BaselineFile.write(
                GuardsPresence.baselineFile(project),
                Baseline.EMPTY.with("gone", RuleBaseline.of(Map.of("examined", 1L), List.of())));
        BuildPlanResult r = build(project, cache);
        assertThat(r.success()).isFalse();
        assertThat(status(r, TaskNames.GUARD_MODEL)).isEqualTo(TaskStatus.FAIL);
        assertThat(r.errors()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("gone");
            assertThat(d.message()).contains("rule-removed");
        });
    }

    private static TaskStatus status(BuildPlanResult r, String step) {
        return r.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static Path scaffold(Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "proj"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(
                src.resolve("App.java"),
                "package demo;\n\npublic final class App {\n    public static void main(String[] a) {}\n}\n");
        Files.writeString(project.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-app]
                kind       = "forbid"
                signatures = ["demo.Banned"]
                instead    = "nothing"
                why        = "a test rule"
                """);
        return project;
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        TestStoreSeed.complete(JkDirs.store());
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
