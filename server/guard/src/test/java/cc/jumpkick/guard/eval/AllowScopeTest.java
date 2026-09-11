// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module lane judges an allow stale only when that module owns every class the allow names; a
 * glob that reaches into a sibling may be earning its keep there.
 */
class AllowScopeTest {

    private static ClassFacts cls(String internal) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), "A.java", List.of(), List.of(), List.of(), Set.of());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "");
    }

    private static Path workspace(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "p"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : List.of("a", "b")) {
            Files.createDirectories(root.resolve(m));
            Files.writeString(
                    root.resolve(m).resolve("jk.toml"), "group = \"p\"\nname = \"" + m + "\"\nversion = \"1\"\n");
        }
        return root;
    }

    private static EvalContext moduleLane(Path root, String module, FactsIndex facts) {
        return new EvalContext(
                Lane.MODULE,
                root,
                module,
                root.resolve(module),
                List.of(root.resolve(module)),
                () -> facts,
                () -> null,
                List::of);
    }

    @Test
    void a_package_glob_that_also_names_a_sibling_s_classes_is_not_judged_here(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        Path bIdx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, root.resolve("b")), "main");
        Files.createDirectories(bIdx.getParent());
        FactsFormat.write(bIdx, index(cls("p/shared/B")));
        FactsIndex aFacts = index(cls("p/shared/A"));

        assertThat(ForbidEvaluator.appliesHere(
                        new Allow("p.shared.**", "logging"), aFacts, moduleLane(root, "a", aFacts)))
                .as("b holds p.shared classes too; b's lane, not a's, may see the allow used")
                .isFalse();
        assertThat(ForbidEvaluator.appliesHere(
                        new Allow("p.shared.A", "one class"), aFacts, moduleLane(root, "a", aFacts)))
                .as("a class only a holds is a's to judge")
                .isTrue();
        assertThat(ForbidEvaluator.appliesHere(new Allow("a", "module"), aFacts, moduleLane(root, "a", aFacts)))
                .as("an allow naming the module is always the module's")
                .isTrue();
    }

    @Test
    void a_glob_the_module_owns_outright_is_judged_here(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        FactsIndex aFacts = index(cls("p/only/A"));
        assertThat(ForbidEvaluator.appliesHere(new Allow("p.only.**", "here"), aFacts, moduleLane(root, "a", aFacts)))
                .isTrue();
        assertThat(ForbidEvaluator.appliesHere(new Allow("q.**", "nowhere"), aFacts, moduleLane(root, "a", aFacts)))
                .as("a glob naming no class here is not this module's to call stale")
                .isFalse();
    }
}
