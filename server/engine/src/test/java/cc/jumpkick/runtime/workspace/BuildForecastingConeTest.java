// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the memo misses only because some modules' own inputs moved, the preflight forecasts those
 * modules and everything downstream of them, and takes every other module's stored verdict.
 */
class BuildForecastingConeTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void an_edit_forecasts_its_downstream_cone_and_nothing_else(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["lib", "app", "other"]
                """);
        module(ws, "lib", "");
        module(ws, "app", "\n[dependencies]\nlib.workspace = true\n");
        module(ws, "other", "");
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        for (BuildGraph.BuildUnit u : graph.topoOrder()) outputs(ws, u);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(ws, graph, false, Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(ws, graph, fps.fingerprints());

        Files.writeString(ws.resolve("other/src/main/java/Other.java"), "class Other { int edited; }\n");
        var otherEdit = BuildForecasting.forecastWithFingerprints(
                graph, ws.resolve("cache"), false, ws, WorkspaceTarget.PACKAGE, false, null);
        assertThat(otherEdit.dirty()).anyMatch(d -> d.endsWith("other"));
        assertThat(walked(otherEdit, "lib"))
                .as("upstream of nothing that changed")
                .isFalse();
        assertThat(walked(otherEdit, "app")).isFalse();
        assertThat(walked(otherEdit, "other")).isTrue();

        // The edit is certified, as a build would: the next one starts from a clean memo again.
        var settled = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(ws, graph, false, Set.of(), settled.fingerprints());
        ModuleInputProvenance.record(ws, graph, settled.fingerprints());
        Files.writeString(ws.resolve("lib/src/main/java/Lib.java"), "public class Lib { public int edited; }\n");
        var libEdit = BuildForecasting.forecastWithFingerprints(
                graph, ws.resolve("cache"), false, ws, WorkspaceTarget.PACKAGE, false, null);
        assertThat(libEdit.dirty()).anyMatch(d -> d.endsWith("lib"));
        assertThat(walked(libEdit, "app")).as("downstream of the edit").isTrue();
        assertThat(walked(libEdit, "other")).isFalse();
    }

    /** A module the memo still holds dirty (a cancelled build) is walked with its dependents. */
    @Test
    void a_module_left_dirty_by_the_last_build_is_walked_with_its_dependents(@TempDir Path ws) throws Exception {
        BuildGraph.Result graph = workspace(ws, false);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(ws, graph, false, Set.of(ws.resolve("lib").toRealPath()), fps.fingerprints());
        ModuleInputProvenance.record(ws, graph, fps.fingerprints());

        Files.writeString(ws.resolve("other/src/main/java/Other.java"), "class Other { int edited; }\n");
        var preflight = BuildForecasting.forecastWithFingerprints(
                graph, ws.resolve("cache"), false, ws, WorkspaceTarget.PACKAGE, false, null);
        assertThat(walked(preflight, "lib")).isTrue();
        assertThat(walked(preflight, "app")).as("a dependent of a dirty module").isTrue();
    }

    /** A root manifest its members inherit from moves only the root's fingerprint: every module is walked. */
    @Test
    void a_root_manifest_edit_walks_the_whole_workspace(@TempDir Path ws) throws Exception {
        BuildGraph.Result graph = workspace(ws, true);
        var fps = PreflightMemo.snapshotFingerprints(graph, false);
        PreflightMemo.storeDirty(ws, graph, false, Set.of(), fps.fingerprints());
        ModuleInputProvenance.record(ws, graph, fps.fingerprints());

        Files.writeString(
                ws.resolve("jk.toml"), Files.readString(ws.resolve("jk.toml")).replace("0.1.0", "0.2.0"));
        BuildGraph.Result edited = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        var preflight = BuildForecasting.forecastWithFingerprints(
                edited, ws.resolve("cache"), false, ws, WorkspaceTarget.PACKAGE, false, null);
        assertThat(walked(preflight, "other")).as("inherits the root's version").isTrue();
        assertThat(walked(preflight, "lib")).isTrue();
    }

    private BuildGraph.Result workspace(Path ws, boolean rootBuilds) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["lib", "app", "other"]
                """);
        if (rootBuilds) {
            Path src = Files.createDirectories(ws.resolve("src/main/java"));
            Files.writeString(src.resolve("Root.java"), "class Root {}\n");
        }
        module(ws, "lib", "version.workspace = true\n");
        module(ws, "app", "version.workspace = true\n\n[dependencies]\nlib.workspace = true\n");
        module(ws, "other", "version.workspace = true\n");
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        for (BuildGraph.BuildUnit u : graph.topoOrder()) outputs(ws, u);
        return graph;
    }

    private static boolean walked(BuildForecasting.Preflight preflight, String module) {
        for (TaskForecast.Module m : preflight.modules()) {
            if (m.dir().endsWith(module)) return !m.steps().isEmpty();
        }
        throw new AssertionError("no module " + module + " in " + preflight.modules());
    }

    private static void module(Path ws, String name, String deps) throws Exception {
        Path dir = Files.createDirectories(ws.resolve(name + "/src/main/java"));
        Files.writeString(ws.resolve(name + "/jk.toml"), "name = \"" + name + "\"\n" + deps);
        String cls = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Files.writeString(dir.resolve(cls + ".java"), "public class " + cls + " {}\n");
    }

    /** What a clean memo needs on disk: the module's classes and jar. */
    private static void outputs(Path ws, BuildGraph.BuildUnit u) throws Exception {
        BuildLayout layout = BuildLayout.of(ws, u.dir(), u.manifest());
        Files.createDirectories(layout.classesDir());
        Files.writeString(layout.classesDir().resolve("X.class"), "class");
        Files.createDirectories(layout.mainJar().getParent());
        Files.write(layout.mainJar(), new byte[] {1, 2, 3});
    }
}
