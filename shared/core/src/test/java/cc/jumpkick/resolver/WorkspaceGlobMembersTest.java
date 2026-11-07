// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.builds.DeclaredDeps;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every reader of {@code [workspace] modules} takes the build's one expansion: a glob member list
 * gives the tree, the flattened view and the declared-dependency scan the members the build loads,
 * and a glob that matches nothing fails each of them with the build's error.
 */
class WorkspaceGlobMembersTest {

    private static final String UNMATCHED = "[workspace] modules entry `libs/*` matches no directory with a jk.toml";

    private static Path workspace(Path root, String modules) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = %s
                """.formatted(modules));
        return root;
    }

    private static void member(Path root, String rel, String dep) throws Exception {
        Path dir = Files.createDirectories(root.resolve(rel));
        String name = dir.getFileName().toString();
        Files.writeString(dir.resolve("jk.toml"), """
                name = "%s"

                [dependencies]
                %s = { group = "com.foo", name = "%s", version = "1.0" }
                """.formatted(name, dep, dep));
    }

    private static Lockfile lock() {
        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        new Lockfile.Artifact("com.foo:alpha", "1.0", "central", "sha256:x", null, List.of()),
                        new Lockfile.Artifact("com.foo:beta", "1.0", "central", "sha256:x", null, List.of()),
                        new Lockfile.Artifact("com.foo:gamma", "1.0", "central", "sha256:x", null, List.of())));
    }

    @Test
    void a_glob_member_list_gives_tree_flatten_and_the_scan_the_modules_the_build_loads(@TempDir Path dir)
            throws Exception {
        Path root = workspace(dir, "[\"libs/*\", \"app\"]");
        member(root, "libs/one", "alpha");
        member(root, "libs/two", "beta");
        member(root, "app", "gamma");
        Files.createDirectories(root.resolve("libs/notes")); // no manifest: no module
        JkBuild rootBuild = JkBuildParser.parseLocal(root.resolve("jk.toml"));

        List<Path> build =
                List.copyOf(WorkspaceLoader.loadModules(root, rootBuild).keySet());
        assertThat(build).containsExactly(root.resolve("libs/one"), root.resolve("libs/two"), root.resolve("app"));
        assertThat(WorkspaceModules.memberDirs(root)).containsExactlyElementsOf(build);
        assertThat(WorkspaceModules.memberDirs(root, rootBuild)).containsExactlyElementsOf(build);
        assertThat(WorkspaceGraph.loadModules(rootBuild, root, FeatureSelection.DEFAULTS).stream()
                        .map(m -> m.build().project().name()))
                .containsExactly("one", "two", "app");

        assertThat(DependencyTree.collectRoots(rootBuild, root))
                .anyMatch(r -> r.startsWith("com.foo:alpha"))
                .anyMatch(r -> r.startsWith("com.foo:beta"))
                .anyMatch(r -> r.startsWith("com.foo:gamma"));
        String tree = DependencyTree.render(
                rootBuild, lock(), root, Integer.MAX_VALUE, DependencyTreeStyle.Styling.plain(), false);
        assertThat(tree).contains("com.acme:one").contains("com.acme:two").contains("com.acme:app");
        String flat = DependencyTree.render(
                rootBuild, lock(), root, Integer.MAX_VALUE, DependencyTreeStyle.Styling.plain(), true);
        assertThat(flat).contains("com.foo:alpha").contains("com.foo:beta").contains("com.foo:gamma");
        assertThat(DeclaredDeps.collect(root))
                .anyMatch(k -> k.contains("alpha"))
                .anyMatch(k -> k.contains("beta"))
                .anyMatch(k -> k.contains("gamma"));
    }

    @Test
    void a_glob_that_matches_no_member_fails_every_reader_with_the_build_s_message(@TempDir Path dir) throws Exception {
        Path root = workspace(dir, "[\"libs/*\", \"app\"]");
        member(root, "app", "gamma");
        Files.createDirectories(root.resolve("libs/notes"));
        JkBuild rootBuild = JkBuildParser.parseLocal(root.resolve("jk.toml"));
        Path app = root.resolve("app");

        List<Supplier<Object>> readers = List.of(
                () -> {
                    try {
                        return WorkspaceLoader.loadModules(root, rootBuild); // the build
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                },
                () -> WorkspaceModules.memberDirs(root),
                () -> WorkspaceModules.memberDirs(root, rootBuild),
                () -> DependencyTree.collectRoots(rootBuild, root),
                () -> DependencyTree.render(
                        rootBuild, lock(), root, Integer.MAX_VALUE, DependencyTreeStyle.Styling.plain(), false),
                () -> DependencyTree.render(
                        rootBuild, lock(), root, Integer.MAX_VALUE, DependencyTreeStyle.Styling.plain(), true),
                () -> WorkspaceGraph.forMember(app, lock()),
                () -> DeclaredDeps.collect(root));
        for (Supplier<Object> reader : readers) {
            assertThatThrownBy(reader::get)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessage(UNMATCHED);
        }
    }
}
