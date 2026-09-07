// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayersEvaluatorTest {

    /** web → service → repo, plus the shortcut web → repo the rule forbids. */
    private static Path workspace(Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["web", "service", "repo"]
                """);
        module(root, "repo", "");
        module(root, "service", "repo = { group = \"t\", name = \"repo\", version = \"0.0.1\" }\n");
        module(
                root,
                "web",
                "service = { group = \"t\", name = \"service\", version = \"0.0.1\" }\n"
                        + "repo = { group = \"t\", name = \"repo\", version = \"0.0.1\" }\n");
        return root;
    }

    private static void module(Path root, String name, String deps) throws IOException {
        Path m = Files.createDirectories(root.resolve(name).resolve("src/main/java/demo/" + name));
        Files.writeString(
                root.resolve(name).resolve("jk.toml"),
                "group = \"t\"\nname = \"" + name + "\"\nversion = \"0.0.1\"\njdk = 25\n"
                        + (deps.isEmpty() ? "" : "\n[dependencies]\n" + deps));
        Files.writeString(m.resolve("A.java"), "package demo." + name + "; public class A {}\n");
    }

    private static ClassFacts cls(String internal, String... refs) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), "A.java", List.of(), List.of(), List.of(), Set.of(refs));
    }

    /** Writes each module's main index with the given classes, where the workspace lane would read it. */
    private static void indexes(Path root, Map<String, List<ClassFacts>> byModule) throws IOException {
        for (var e : byModule.entrySet()) {
            Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, root.resolve(e.getKey())), "main");
            Files.createDirectories(idx.getParent());
            Map<String, ClassFacts> classes = new LinkedHashMap<>();
            for (ClassFacts c : e.getValue()) classes.put(c.name(), c);
            FactsFormat.write(idx, new FactsIndex(classes, Map.of(), "x"));
        }
    }

    private static Evaluation run(Path root, String body, Lane lane) throws Exception {
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"layers\"\nwhy = \"w\"\n" + body);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("r").orElseThrow();
        assertThat(Evaluators.laneOf(rule)).isEqualTo(lane);
        List<Path> modules = List.of(root.resolve("web"), root.resolve("service"), root.resolve("repo"));
        EvalContext ctx = new EvalContext(
                lane,
                root,
                "",
                null,
                modules,
                EvalContext.lazy(
                        () -> lane == Lane.WORKSPACE ? WorkspaceFacts.merged(root, modules) : FactsIndex.EMPTY),
                () -> null,
                List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void manifest_edges_run_in_the_model_lane(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        String rule = "layers = { web = \"web\", service = \"service\", repo = \"repo\" }\n"
                + "access = { web = [\"service\"], service = [\"repo\"] }\n";
        Evaluation e = run(root, rule, Lane.MODEL);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("module:web -> repo");
            assertThat(o.file()).isEqualTo("web/jk.toml");
            assertThat(o.detail()).contains("web (web) depends on repo (repo); web may depend on service");
        });
        assertThat(e.population()).containsEntry("edges", 3L).containsEntry("layer:web", 1L);
        Evaluation allowed =
                run(root, rule + "[[guards.r.allow]]\nin = \"web\"\nreason = \"legacy shortcut\"\n", Lane.MODEL);
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation globbed =
                run(root, "layers = { app = \"we*\", lib = \"*e*o\" }\naccess = { app = [\"lib\"] }\n", Lane.MODEL);
        assertThat(globbed.outcome()).as("service → repo is lib → lib").isEqualTo(Outcome.CLEAN);
    }

    @Test
    void class_edges_exports_and_exact_run_in_the_workspace_lane(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        indexes(
                root,
                Map.of(
                        "web", List.of(cls("demo/web/A", "demo/service/A", "demo/repo/A", "demo/repo/Internal")),
                        "service", List.of(cls("demo/service/A", "demo/repo/A")),
                        "repo", List.of(cls("demo/repo/A"), cls("demo/repo/Internal"))));
        String rule = "layers = { web = \"..web..\", service = \"..service..\", repo = \"..repo..\" }\n"
                + "access = { web = [\"service\"], service = [\"repo\"] }\nedges = \"classes\"\n";
        Evaluation e = run(root, rule, Lane.WORKSPACE);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder("demo.web.A -> demo.repo.A", "demo.web.A -> demo.repo.Internal");
        assertThat(e.population()).containsEntry("edges", 4L);

        Evaluation exports = run(
                root,
                "layers = { all = \"..demo..\" }\naccess = { all = [\"all\"] }\nedges = \"classes\"\n"
                        + "exports = { repo = [\"demo.repo\"] }\n",
                Lane.WORKSPACE);
        assertThat(exports.observations())
                .extracting(Observation::key)
                .as("Internal is in the exported package; a package pattern, not a class list")
                .isEmpty();
        Evaluation notExported = run(
                root,
                "layers = { all = \"..demo..\" }\naccess = { all = [\"all\"] }\nedges = \"classes\"\n"
                        + "exports = { repo = [\"demo.repo.api..\"] }\n",
                Lane.WORKSPACE);
        assertThat(notExported.observations())
                .extracting(Observation::key)
                .contains("demo.web.A -> demo.repo.A | export", "demo.service.A -> demo.repo.A | export");

        // exact: web declares repo and uses it; a declared-but-unused dependency appears once repo's classes go
        // unreferenced.
        indexes(
                root,
                Map.of(
                        "web", List.of(cls("demo/web/A", "demo/service/A")),
                        "service", List.of(cls("demo/service/A", "demo/repo/A")),
                        "repo", List.of(cls("demo/repo/A"))));
        Evaluation exact = run(
                root,
                "layers = { web = \"web\", service = \"service\", repo = \"repo\" }\naccess = { web = [\"service\", \"repo\"], service = [\"repo\"] }\n"
                        + "edges = \"both\"\nexact = true\n",
                Lane.WORKSPACE);
        assertThat(exact.observations()).extracting(Observation::key).containsExactly("module:web -> repo | unused");
        assertThat(exact.observations().get(0).detail())
                .contains("declares a dependency on repo that no class of it uses");
    }

    @Test
    void an_empty_layer_or_no_edge_is_blind_and_unknown_layers_fail(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        Evaluation blind = run(
                root,
                "layers = { web = \"web\", ghost = \"nothing/here\" }\naccess = { web = [\"ghost\"] }\n",
                Lane.MODEL);
        assertThat(blind.outcome()).isEqualTo(Outcome.BLIND);
        assertThat(blind.note()).contains("ghost");
        Evaluation unknown = run(root, "layers = { web = \"web\" }\naccess = { web = [\"nope\"] }\n", Lane.MODEL);
        assertThat(unknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        Evaluation exactNeedsClasses =
                run(root, "layers = { web = \"web\" }\naccess = { web = [] }\nexact = true\n", Lane.MODEL);
        assertThat(exactNeedsClasses.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
    }
}
