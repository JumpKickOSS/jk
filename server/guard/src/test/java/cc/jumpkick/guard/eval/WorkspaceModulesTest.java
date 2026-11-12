// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.Settled;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

/** A glob member list means the same modules to every guard lane as it does to the build. */
class WorkspaceModulesTest {

    private static Path workspace(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "p"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["services/*", "lib"]
                """);
        for (String m : List.of("services/b", "services/a", "lib")) {
            Path d = Files.createDirectories(root.resolve(m));
            String name = m.substring(m.lastIndexOf('/') + 1);
            Files.writeString(d.resolve("jk.toml"), "group = \"p\"\nname = \"" + name + "\"\nversion = \"1\"\n");
        }
        // a directory the glob reaches that holds no manifest is no module
        Files.createDirectories(root.resolve("services/notes"));
        return root;
    }

    @Test
    void a_glob_member_list_gives_guard_lanes_the_modules_the_build_loads(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        List<Path> build =
                List.copyOf(WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml")))
                        .keySet());

        assertThat(build).containsExactly(root.resolve("services/a"), root.resolve("services/b"), root.resolve("lib"));
        assertThat(WorkspaceModules.of(root))
                .as("the lanes' member list is the build's, glob expanded")
                .containsExactlyElementsOf(build);
        assertThat(WorkspaceModules.members(root, JkBuildParser.parse(root.resolve("jk.toml"))))
                .containsExactlyElementsOf(build);
    }

    @Test
    void a_file_under_a_glob_member_is_judged_by_that_member(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        FileModules files = FileModules.of(new EvalContext(
                Lane.TREE, root, "", null, WorkspaceModules.of(root), () -> FactsIndex.EMPTY, () -> null, List::of));

        assertThat(files.moduleOf("services/a/src/main/java/A.java")).isEqualTo("services/a");
        assertThat(files.moduleOf("services/notes/README.md"))
                .as("no manifest, no module: the root holds it")
                .isEmpty();
    }

    @Test
    void a_glob_member_s_rule_file_loads_and_the_extractor_lists_the_modules(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.root-rule]
                kind = "text"
                pattern = "zzz"
                hit = "zzz"
                instead = "i"
                why = "root"
                """);
        Files.writeString(root.resolve("services/a").resolve(GuardsPresence.RULES_FILE), """
                [guards.member-rule]
                kind = "text"
                pattern = "yyy"
                hit = "yyy"
                instead = "i"
                why = "member"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.rules().sourceDigests())
                .as("the member layer reads the rule file of a member the glob selects")
                .containsKey("services/a/" + GuardsPresence.RULES_FILE);

        EvalContext ctx = new EvalContext(
                        Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        Extractors.Spec spec = Objects.requireNonNull(Extractors.parse(
                Toml.parse("x = { workspace-modules = \"jk.toml\" }").getTable("x")));
        assertThat(Extractors.extract(
                                spec,
                                Objects.requireNonNull(load.rules().rules().get("root-rule")),
                                ctx)
                        .keys())
                .containsExactly("lib", "services/a", "services/b");
    }

    @Test
    void a_glob_that_matches_no_member_fails_the_lanes_and_the_member_layer_as_it_fails_the_build(@TempDir Path dir)
            throws Exception {
        Path root = workspace(dir);
        Files.writeString(root.resolve("jk.toml"), """
                group = "p"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["apps/*", "lib"]
                """);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.root-rule]
                kind = "text"
                pattern = "zzz"
                hit = "zzz"
                instead = "i"
                why = "root"
                """);
        String message = "[workspace] modules entry `apps/*` matches no directory with a jk.toml";

        assertThatThrownBy(() -> WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml"))))
                .as("the build")
                .hasMessage(message);
        assertThatThrownBy(() -> WorkspaceModules.of(root)).hasMessage(message);
        assertThatThrownBy(() -> GuardRules.load(root, GuardsConfig.ABSENT))
                .as("the member rule layer")
                .hasMessage(message);
    }

    @Test
    void a_member_added_under_a_glob_is_seen_by_the_next_lane(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        // Judged as settled, so the memo answers from its stamp rather than re-reading a fresh write.
        Settled.aheadForTests(Duration.ofMinutes(1));
        try {
            assertThat(WorkspaceModules.of(root))
                    .containsExactly(root.resolve("services/a"), root.resolve("services/b"), root.resolve("lib"));

            // A new member under the glob edits no existing file.
            Path c = Files.createDirectories(root.resolve("services/c"));
            Files.writeString(c.resolve("jk.toml"), "group = \"p\"\nname = \"c\"\nversion = \"1\"\n");

            assertThat(WorkspaceModules.of(root))
                    .containsExactly(
                            root.resolve("services/a"),
                            root.resolve("services/b"),
                            root.resolve("services/c"),
                            root.resolve("lib"));
        } finally {
            Settled.aheadForTests(null);
        }
    }
}
