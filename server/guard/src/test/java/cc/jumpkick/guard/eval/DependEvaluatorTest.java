// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependEvaluatorTest {

    private static void workspace(Path root) throws IOException {
        Files.writeString(root.resolve(ManifestPaths.MANIFEST), """
                group = "acme"
                name = "ws"
                version = "1.0.0"
                jdk = 25

                [workspace]
                modules = ["app", "lib"]
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/" + ManifestPaths.MANIFEST), """
                name = "app"
                group = "acme"
                version = "1.0.0"

                [dependencies]
                junit = { group = "junit", name = "junit", version = "=4.13.2" }
                lombok = { group = "org.projectlombok", name = "lombok", version = "=1.18.36" }
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }

                [processor-dependencies]
                lombok-ap = { group = "org.projectlombok", name = "lombok", version = "=1.18.36" }
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/" + ManifestPaths.MANIFEST), """
                name = "lib"
                group = "acme"
                version = "1.0.0"

                [dependencies]
                log4j = { group = "org.apache.logging.log4j", name = "log4j-core", version = "=2.14.1" }
                """);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                null,
                null,
                List.of(
                        artifact("junit:junit:jar:", "4.13.2", Scope.MAIN),
                        artifact("org.projectlombok:lombok:jar:", "1.18.36", Scope.MAIN),
                        artifact("com.google.guava:guava:jar:", "33.4.0-jre", Scope.MAIN),
                        artifact("org.apache.logging.log4j:log4j-core:jar:", "2.14.1", Scope.MAIN),
                        artifact("org.apache.logging.log4j:log4j-api:jar:", "2.14.1", Scope.MAIN),
                        artifact("org.slf4j:slf4j-api:jar:", "1.7.36", Scope.MAIN),
                        artifact("org.slf4j:slf4j-api:jar:", "2.0.16", Scope.TEST),
                        artifact("acme:internal:jar:", "1.0-SNAPSHOT", Scope.MAIN)),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
        LockfileWriter.write(lock, root.resolve(ManifestPaths.LOCK));
    }

    private static Lockfile.Artifact artifact(String name, String version, Scope scope) {
        return new Lockfile.Artifact(
                name, version, "central+https://repo.example/", null, null, List.of(scope), List.of(), null);
    }

    private static Map<String, Evaluation> run(Path root, String rules) throws Exception {
        workspace(root);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                Lane.MODEL,
                root,
                "",
                null,
                List.of(root.resolve("app"), root.resolve("lib")),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.MODEL, load.rules(), ""), ctx);
    }

    private static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    @Test
    void ban_sees_declared_and_resolved_coordinates(@TempDir Path root) throws Exception {
        Map<String, Evaluation> r = run(root, """
                [guards.no-junit4]
                kind = "depend"
                ban = ["junit:junit", "org.apache.logging.log4j:*"]
                instead = "junit-jupiter"
                why = "w"
                """);
        Evaluation e = ev(r, "no-junit4");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::key)
                .contains(
                        "app [dependencies] junit:junit",
                        "lock junit:junit",
                        "lib [dependencies] org.apache.logging.log4j:log4j-core",
                        "lock org.apache.logging.log4j:log4j-api");
        assertThat(e.population()).containsEntry("declared", 5L).containsEntry("artifacts", 8L);
    }

    @Test
    void scope_placement_floors_convergence_dynamic_and_snapshot(@TempDir Path root) throws Exception {
        Map<String, Evaluation> r = run(root, """
                [guards.lombok-scope]
                kind = "depend"
                coordinate = "org.projectlombok:lombok"
                only-in = ["processor-dependencies", "provided-dependencies"]
                instead = "jk add --scope processor lombok"
                why = "w"
                [guards.floors]
                kind = "depend"
                require = { "org.apache.logging.log4j:*" = ">=2.17.1", "com.google.guava:*" = ">=33" }
                convergence = true
                no-dynamic = true
                no-snapshot = true
                why = "w"
                """);
        assertThat(ev(r, "lombok-scope").observations())
                .singleElement()
                .extracting(Observation::key)
                .isEqualTo("app [dependencies] org.projectlombok:lombok");
        Evaluation floors = ev(r, "floors");
        assertThat(floors.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder(
                        "floor org.apache.logging.log4j:log4j-core",
                        "floor org.apache.logging.log4j:log4j-api",
                        "convergence org.slf4j:slf4j-api",
                        "dynamic app [dependencies] com.google.guava:guava",
                        "snapshot lock acme:internal");
    }

    @Test
    void allow_licenses_and_errors(@TempDir Path root) throws Exception {
        Map<String, Evaluation> r = run(root, """
                [guards.allowed]
                kind = "depend"
                ban = ["junit:junit"]
                allow = [{ in = "app", reason = "legacy suite" }, { in = "junit:junit", reason = "the lock row" }]
                instead = "x"
                why = "w"
                [guards.stale]
                kind = "depend"
                ban = ["junit:junit"]
                allow = [{ in = "nowhere", reason = "gone" }]
                instead = "x"
                why = "w"
                [guards.lic]
                kind = "depend"
                licenses = { forbid = ["GPL-*"] }
                why = "w"
                [guards.bad-range]
                kind = "depend"
                require = { "a:b" = "banana" }
                why = "w"
                """);
        assertThat(ev(r, "allowed").outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(ev(r, "stale").outcome()).isEqualTo(Outcome.STALE_ALLOW);
        assertThat(ev(r, "lic").outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(ev(r, "bad-range").outcome())
                .as("a bare version is exact, so it parses; nothing matches a:b")
                .isEqualTo(Outcome.CLEAN);
        assertThat(VersionRanges.satisfies("2.17.1", ">=2.17.1")).isTrue();
        assertThat(VersionRanges.satisfies("2.14.1", ">=2.17.1")).isFalse();
        assertThat(VersionRanges.satisfies("3.0.0", ">=2.17, <3")).isFalse();
        assertThat(VersionRanges.valid(">=")).isFalse();
    }

    @Test
    void proposed_manifest_text_is_judged_before_a_write(@TempDir Path root) throws Exception {
        workspace(root);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-junit4]
                kind = "depend"
                ban = ["junit:junit"]
                instead = "junit-jupiter"
                why = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        String proposed =
                "name = \"m\"\ngroup = \"g\"\nversion = \"1\"\n\n[dependencies]\njunit = { group = \"junit\", name = \"junit\", version = \"=4.13.2\" }\n";
        Evaluation e =
                DependEvaluator.evaluateProposed(load.rules().rule("no-junit4").orElseThrow(), "m", proposed, null);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .singleElement()
                .extracting(Observation::key)
                .isEqualTo("m [dependencies] junit:junit");
    }

    @Test
    void a_scoped_rule_reads_the_scoped_manifests_and_not_the_lock(@TempDir Path root) throws Exception {
        Map<String, Evaluation> r = run(root, """
                [guards.app-only]
                kind = "depend"
                scope = ["app"]
                ban = ["org.apache.logging.log4j:*", "junit:junit"]
                instead = "slf4j"
                why = "w"

                [guards.scoped-floor]
                kind = "depend"
                scope = ["app"]
                require = { "junit:junit" = ">=5" }
                why = "w"
                """);
        Evaluation e = ev(r, "app-only");
        // lib declares log4j and the lock resolves it, but the rule is scoped to app: only app's junit is a site
        assertThat(e.observations()).extracting(Observation::key).containsExactly("app [dependencies] junit:junit");
        assertThat(e.population()).containsEntry("declared", 4L).containsEntry("artifacts", 0L);
        assertThat(ev(r, "scoped-floor").outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(ev(r, "scoped-floor").note()).contains("workspace-wide");
    }

    @Test
    void a_module_outside_the_root_is_keyed_by_its_relative_spelling_not_an_absolute_path(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        workspace(root);
        Path sibling = Files.createDirectories(tmp.resolve("sib"));
        Files.writeString(sibling.resolve(ManifestPaths.MANIFEST), """
                name = "sib"
                group = "acme"
                version = "1.0.0"

                [dependencies]
                junit = { group = "junit", name = "junit", version = "=4.13.2" }
                """);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-junit4]
                kind = "depend"
                ban = ["junit:junit"]
                instead = "junit-jupiter"
                why = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                Lane.MODEL,
                root,
                "",
                null,
                List.of(root.resolve("app"), root.resolve("lib"), sibling),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        Evaluation e = ev(LaneRun.evaluate(LaneRun.rulesFor(Lane.MODEL, load.rules(), ""), ctx), "no-junit4");
        assertThat(e.observations())
                .extracting(Observation::key)
                .contains("../sib [dependencies] junit:junit")
                .noneMatch(k -> k.contains(tmp.toString()));
    }
}
