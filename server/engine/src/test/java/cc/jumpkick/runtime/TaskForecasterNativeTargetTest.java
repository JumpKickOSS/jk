// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-2088: native forecast eligibility must come from the resolved terminal set (what
 * assemblePlan will actually build), not from re-derived {@code [native]} tables. A fallback
 * (table-less unique-main) module was invisible to the forecast; an unselected cone prereq WITH
 * a table was priced perpetually dirty even though its plan gets {@code allowNative=false}.
 */
class TaskForecasterNativeTargetTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    /** One-module workspace; {@code nativeTable} adds an explicit [native] table. */
    private static Path workspace(Path tmp, boolean nativeTable) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(tmp.resolve("app")).toRealPath();
        String manifest = """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """;
        if (nativeTable) manifest += "\n[native]\nenabled = true\n";
        Files.writeString(app.resolve("jk.toml"), manifest);
        Path src = Files.createDirectories(app.resolve("src/main/java/t"));
        Files.writeString(src.resolve("App.java"), "package t; class App { public static void main(String[] a) {} }\n");
        Lockfile lf = new Lockfile(
                Lockfile.CURRENT_VERSION, "test", "pubgrub-v1", null, null, List.of(), List.of(), List.of());
        LockfileWriter.write(lf, tmp.resolve("jk-lock.toml"), LockManifestDigest.compute(tmp));
        return app;
    }

    private static List<TaskForecast.Module> forecast(Path root, Set<Path> terminalDirs) throws Exception {
        BuildGraph.Result graph =
                BuildGraph.resolve(root, JkBuildParser.parse(Files.readString(root.resolve("jk.toml"))));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = root.resolve("cache");
        return SessionContext.where(Session.defaults(), () -> {
            var cas = JkStores.cas(cache);
            var ac = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
            return TaskForecaster.of(graph, cas, ac, cache, false, WorkspaceTarget.NATIVE, terminalDirs);
        });
    }

    private static final Function<TaskForecast.Module, List<String>> STEP_NAMES =
            m -> m.steps().stream().map(TaskForecast.Task::name).toList();

    @Test
    void fallback_module_in_terminal_set_gets_a_native_image_step(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Path app = workspace(root, false); // no [native] table — documented unique-main fallback
        var m = forecast(root, Set.of(app)).stream()
                .filter(x -> x.dir().equals(app))
                .findFirst()
                .orElseThrow();
        assertThat(STEP_NAMES.apply(m)).contains("native-image");
        assertThat(m.dirty()).isTrue();
    }

    @Test
    void unselected_table_module_gets_no_native_image_step(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Path app = workspace(root, true); // [native] table, but NOT in the terminal set
        var m = forecast(root, Set.of()).stream()
                .filter(x -> x.dir().equals(app))
                .findFirst()
                .orElseThrow();
        assertThat(STEP_NAMES.apply(m)).doesNotContain("native-image");
    }
}
