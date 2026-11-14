// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.runtime.BuildKnobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --verify-cache --verify-sample N} samples the modules a plain build would skip and leaves
 * the rest as a plain build leaves them. A module whose inputs are clean but whose outputs are gone
 * is one a plain build schedules to restore, so it is never among the skippable ones a sample may
 * drop: dropped, its dependents fail to resolve it.
 */
class VerifySampleRestoreTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_module_whose_outputs_are_gone_is_not_claimed_clean(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        BuildLayout layout =
                BuildLayout.of(tmp, tmp, graph.topoOrder().getFirst().manifest());
        Path classFile = layout.classesDir().resolve("App.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "bytecode");
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar");
        PreflightMemo.storeDirty(
                tmp,
                graph,
                false,
                Set.of(),
                PreflightMemo.snapshotFingerprints(graph, false).fingerprints());

        // jk clean: the memo still vouches for the inputs, the outputs are gone.
        PathUtil.deleteRecursively(layout.moduleTargetDir());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false).orElseThrow().restoreNeeded())
                .isNotEmpty();

        Set<Path> clean = BuildForecasting.claimedClean(graph, tmp.resolve("cache"), tmp, BuildKnobs.DEFAULTS);
        assertThat(clean)
                .as("a plain build restores this module; a sample must not leave it out")
                .doesNotContain(graph.topoOrder().getFirst().dir());
    }
}
