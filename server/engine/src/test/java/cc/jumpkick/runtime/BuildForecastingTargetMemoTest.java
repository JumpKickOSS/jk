// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *: the preflight dirty memo certifies package outputs only. A terminal-target run
 * (native/image) hitting a PACKAGE-stored all-clean memo would skip the target-aware forecast
 * and never produce its binary ({@code jk build && jk native} reporting success with no
 * artifact). Terminal targets must neither consult nor store the memo.
 */
class BuildForecastingTargetMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static BuildGraph.Result writeProjectAndResolve(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        // Clean-claim requires the module output tree to exist.
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp, tmp));
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    @Test
    void native_target_bypasses_and_preserves_the_package_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = writeProjectAndResolve(tmp);
        assertThat(graph.hasErrors()).isFalse();

        // A successful jk build stores an all-clean memo.
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), fps);
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Path cache = tmp.resolve("cache");

        // PACKAGE trusts the memo: fully cached, no forecast walk.
        var pkg = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.PACKAGE);
        assertThat(pkg.dirty()).isEmpty();

        // NATIVE must ignore the memo and run the target-aware walk (fresh cache ⇒ dirty).
        var nat = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.NATIVE);
        assertThat(nat.dirty()).isNotEmpty();

        // ...and must not have overwritten the package memo with its own verdict.
        var reload = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(reload).isPresent();
        assertThat(reload.get().dirty()).isEmpty();

        // IMAGE behaves like NATIVE.
        var img = BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.IMAGE);
        assertThat(img.dirty()).isNotEmpty();
    }
}
