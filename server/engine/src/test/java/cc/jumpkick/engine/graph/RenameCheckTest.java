// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's half of a rename's proof, the same for CLI and MCP: compile the modules the rename
 * wrote in (main and tests), then ask the worker to verify; undo when the compile failed; leave the
 * edit, and say so, when the compile could not run.
 */
class RenameCheckTest {

    private static Path workspace(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "a"
                name = "shop"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        for (String m : List.of("core", "app")) {
            Files.createDirectories(dir.resolve(m));
            Files.writeString(dir.resolve(m).resolve("jk.toml"), "name = \"" + m + "\"\n");
        }
        Path proof = RenameCheck.proof(dir);
        Files.createDirectories(proof.getParent());
        Files.writeString(proof, "modules\tcore/\nsym\tT a.A\tT a.B\n");
        return dir;
    }

    @Test
    void aCompileThatPassesIsFollowedByTheWorkersProofForTheModulesItWroteIn(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        List<String> compiled = new ArrayList<>();
        List<List<String>> asked = new ArrayList<>();
        String line = RenameCheck.prove(
                root,
                "rename",
                true,
                (r, modules, tests) -> {
                    compiled.add(modules + " tests=" + tests);
                    return null;
                },
                options -> {
                    asked.add(options);
                    return "Proven: it compiles";
                });
        assertThat(compiled).containsExactly("[core] tests=true");
        assertThat(asked).containsExactly(List.of("phase=verify"));
        assertThat(line).isEqualTo("Proven: it compiles");
    }

    @Test
    void aCompileThatFailsUndoesTheRenameWithTheReason(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        List<List<String>> asked = new ArrayList<>();
        RenameCheck.prove(
                root,
                "rename",
                true,
                (r, modules, tests) -> "the code does not compile (A.java:3: cannot find symbol)",
                o -> {
                    asked.add(o);
                    return "Rolled back";
                });
        assertThat(asked)
                .containsExactly(List.of("phase=undo", "why=the code does not compile (A.java:3: cannot find symbol)"));
    }

    @Test
    void aCompileThatCouldNotRunLeavesTheEditAndSaysSo(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        String line = RenameCheck.prove(
                root, "rename", true, (r, modules, tests) -> "a build of this checkout is running", o -> {
                    throw new AssertionError("no check without a compile");
                });
        assertThat(line)
                .isEqualTo(
                        "Not proven: a build of this checkout is running. The edit stays; build and test to check it.");
    }

    @Test
    void aDeleteWhoseTestsDidNotCompileBeforeCompilesMainAloneAndSaysTheTestsAreNotChecked(@TempDir Path dir)
            throws Exception {
        Path root = workspace(dir);
        List<String> compiled = new ArrayList<>();
        String line = RenameCheck.prove(
                root,
                "delete",
                false,
                (r, modules, tests) -> {
                    compiled.add(modules + " tests=" + tests);
                    return null;
                },
                options -> "Proven: it compiles.");
        assertThat(compiled).containsExactly("[core] tests=false");
        assertThat(line)
                .isEqualTo("Proven: it compiles.\nNot checked: the tests did not compile before the delete, so their"
                        + " uses of it are not known; not done until they compile.");
    }
}
