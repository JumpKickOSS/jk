// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scope-aware dep dirtiness: a dirty <em>test-only</em> sibling must not force main
 * compile/package/native (jk-cli ← jk-engine via {@code [test-dependencies]}).
 */
class TaskForecasterDepScopeTest {

    @Test
    void test_only_sibling_is_testDepDirty_not_compileDepDirty(@TempDir Path tmp) throws Exception {
        Path eng = tmp.resolve("engine");
        Path cli = tmp.resolve("cli");
        Files.createDirectories(eng);
        Files.createDirectories(cli);
        Files.writeString(eng.resolve("jk.toml"), """
                group = "cc.example"
                name = "engine"
                version = "1.0.0"
                java = 25
                """);
        Files.writeString(cli.resolve("jk.toml"), """
                group = "cc.example"
                name = "cli"
                version = "1.0.0"
                java = 25

                [dependencies]
                core.workspace = true

                [test-dependencies]
                engine.workspace = true
                """);
        Path core = tmp.resolve("core");
        Files.createDirectories(core);
        Files.writeString(core.resolve("jk.toml"), """
                group = "cc.example"
                name = "core"
                version = "1.0.0"
                java = 25
                """);

        JkBuild cliBuild = JkBuildParser.parse(cli.resolve("jk.toml"));
        var unit = new BuildGraph.BuildUnit(cli, cliBuild, "cc.example:cli", BuildGraph.Origin.MODULE);
        Map<String, Path> byCoord = new HashMap<>();
        Map<String, Path> byName = new HashMap<>();
        byCoord.put("cc.example:cli", cli);
        byCoord.put("cc.example:engine", eng);
        byCoord.put("cc.example:core", core);
        byName.put("cli", cli);
        byName.put("engine", eng);
        byName.put("core", core);

        // Engine dirty, core clean — engine is test-only for cli
        var onlyEngine = TaskForecaster.depDirtiness(unit, Set.of(eng, core), Set.of(eng), byCoord, byName);
        assertThat(onlyEngine.compileDepDirty()).isFalse();
        assertThat(onlyEngine.testDepDirty()).isTrue();

        // Core dirty — main compile dep
        var onlyCore = TaskForecaster.depDirtiness(unit, Set.of(eng, core), Set.of(core), byCoord, byName);
        assertThat(onlyCore.compileDepDirty()).isTrue();
        assertThat(onlyCore.testDepDirty()).isFalse();

        // Both dirty
        var both = TaskForecaster.depDirtiness(unit, Set.of(eng, core), Set.of(eng, core), byCoord, byName);
        assertThat(both.compileDepDirty()).isTrue();
        assertThat(both.testDepDirty()).isTrue();
    }

    @Test
    void clean_prereqs_are_none(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib");
        Path app = tmp.resolve("app");
        Files.createDirectories(lib);
        Files.createDirectories(app);
        Files.writeString(lib.resolve("jk.toml"), """
                group = "cc.example"
                name = "lib"
                version = "1.0.0"
                java = 25
                """);
        Files.writeString(app.resolve("jk.toml"), """
                group = "cc.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib.workspace = true
                """);
        JkBuild appBuild = JkBuildParser.parse(app.resolve("jk.toml"));
        var unit = new BuildGraph.BuildUnit(app, appBuild, "cc.example:app", BuildGraph.Origin.MODULE);
        Map<String, Path> byCoord = Map.of("cc.example:app", app, "cc.example:lib", lib);
        Map<String, Path> byName = Map.of("app", app, "lib", lib);
        var d = TaskForecaster.depDirtiness(unit, Set.of(lib), Set.of(), byCoord, byName);
        assertThat(d.compileDepDirty()).isFalse();
        assertThat(d.testDepDirty()).isFalse();
    }

    @Test
    void order_after_only_dirty_prereq_is_orderDepDirty(@TempDir Path tmp) throws Exception {
        // A dirty order-after-only sibling (incl. test-plugin-jars) prices no compile/test work
        // but must schedule the dependent so real action keys re-check out-of-band outputs.
        Path gen = tmp.resolve("gen");
        Path app = tmp.resolve("app");
        Files.createDirectories(gen);
        Files.createDirectories(app);
        Files.writeString(gen.resolve("jk.toml"), """
                group = "cc.example"
                name = "gen"
                version = "1.0.0"
                java = 25
                """);
        Files.writeString(app.resolve("jk.toml"), """
                group = "cc.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [build]
                order-after = ["gen"]
                """);
        JkBuild appBuild = JkBuildParser.parse(app.resolve("jk.toml"));
        var unit = new BuildGraph.BuildUnit(app, appBuild, "cc.example:app", BuildGraph.Origin.MODULE);
        Map<String, Path> byCoord = Map.of("cc.example:app", app, "cc.example:gen", gen);
        Map<String, Path> byName = Map.of("app", app, "gen", gen);

        var d = TaskForecaster.depDirtiness(unit, Set.of(gen), Set.of(gen), byCoord, byName);
        assertThat(d.compileDepDirty()).isFalse();
        assertThat(d.testDepDirty()).isFalse();
        assertThat(d.orderDepDirty()).isTrue();

        // An order-check RUN task is material, so the dependent schedules.
        assertThat(cc.jumpkick.runtime.TaskForecast.Module.isMaterialWork("order-check"))
                .isTrue();
    }
}
