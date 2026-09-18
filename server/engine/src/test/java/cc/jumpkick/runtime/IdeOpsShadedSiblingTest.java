// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.IdeOps;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The IDE model hands a relocating sibling to its consumers as its {@code -all.jar}, a library
 * entry, not a module edge: the IDE then resolves the shaded names jk compiles against, and a
 * plain sibling is still the module edge the IDE compiles itself.
 */
class IdeOpsShadedSiblingTest {

    @Test
    void a_relocating_sibling_is_a_library_entry_on_its_all_jar_not_a_module_edge(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["shaded", "plain", "app"]
                """);
        module(root, "shaded", """
                [library]
                relocate = { "com.foo" = "com.ex.shaded.foo" }
                """);
        module(root, "plain", "");
        Path app = module(root, "app", """
                [dependencies]
                shaded = { workspace = true }
                plain = { workspace = true }

                [test-dependencies]
                plain-tests = { workspace = true, name = "plain", kind = "tests" }
                """);
        JkBuild rootBuild = JkBuildParser.parse(root.resolve("jk.toml"));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, rootBuild);
        BuildLayout shaded =
                BuildLayout.of(root, root.resolve("shaded"), requireNonNull(modules.get(root.resolve("shaded"))));

        IdeOps.SiblingEdges edges = IdeOps.siblingEdges(app, requireNonNull(modules.get(app)), modules);

        assertThat(edges.moduleRefs())
                .extracting(r -> r[0], r -> r[1])
                .as("the plain sibling is a module edge, its test classes attached")
                .containsExactly(tuple("plain", IdeWireModel.SCOPE_COMPILE_TEST_KIND));
        String libName = "com.ex:shaded:0.1.0-all";
        assertThat(edges.libEntries()).extracting(r -> r[0], r -> r[1]).containsExactly(tuple(libName, "MAIN"));
        assertThat(edges.libDefs()).containsOnlyKeys(libName);
        assertThat(requireNonNull(edges.libDefs().get(libName))[1])
                .isEqualTo(shaded.assemblyJar().toString());
    }

    private static Path module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"

                %s""".formatted(name, tables));
        return dir;
    }
}
