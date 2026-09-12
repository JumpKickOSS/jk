// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast's Kotlin/Groovy source lists come from {@code PlannerCompile.main*Sources}, which
 * folds in {@code [build] extra-src} and plugin-contributed roots exactly like the live build.
 * A bare {@code src/} walk has two failure shapes, both pinned here: a module whose Groovy lives
 * only under an extra root forecasts no compile step at all (the emptiness gate), and a module
 * with both roots stamps freshness against a subset, blessing an edit under the extra root.
 */
class TaskForecasterExtraRootTest {

    @Test
    void groovy_only_under_extra_root_still_forecasts_the_compile_step(@TempDir Path tmp) throws Exception {
        Path mod = workspaceWithModule(tmp);
        Path services = Files.createDirectories(mod.resolve("grails-app/services"));
        Path bar = services.resolve("Bar.groovy");
        Files.writeString(bar, "class Bar {}");
        agedByAnHour(bar);
        lock(tmp);

        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        List<TaskForecast.Module> plan = TaskForecaster.of(graph, cas, actionCache, cache);
        TaskForecast.Module a =
                plan.stream().filter(m -> m.dir().endsWith("a")).findFirst().orElseThrow();
        TaskForecast.Task gv = a.steps().stream()
                .filter(s -> s.name().equals("compile-groovy"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "compile-groovy missing from the forecast — the extra-src walk was not folded in; steps: "
                                + a.steps().stream()
                                        .map(TaskForecast.Task::name)
                                        .toList()));
        assertThat(gv.cached()).isFalse();
    }

    @Test
    void edit_under_extra_root_is_not_forecast_cached(@TempDir Path tmp) throws Exception {
        Path mod = workspaceWithModule(tmp);
        Path src = Files.createDirectories(mod.resolve("src"));
        Path foo = src.resolve("Foo.groovy");
        Files.writeString(foo, "class Foo {}");
        agedByAnHour(foo);
        Path services = Files.createDirectories(mod.resolve("grails-app/services"));
        Path bar = services.resolve("Bar.groovy");
        Files.writeString(bar, "class Bar {}");
        agedByAnHour(bar);
        lock(tmp);

        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        // A stored build stamped every source the compile actually read — both roots.
        var layout = BuildLayout.of(mod, JkBuildParser.parse(mod.resolve("jk.toml")));
        FreshnessStamp.write(
                layout.classesDir(), BuildStamps.GROOVY, "compile-groovy", "", List.of(foo, bar), List.of(), 21, "");
        TaskForecast.Task warm = groovyStep(TaskForecaster.of(graph, cas, actionCache, cache));
        assertThat(warm.cached()).isTrue();

        // An edit under the extra root: a subset stamp check (src/ only) would still say CACHED.
        Files.setLastModifiedTime(bar, FileTime.fromMillis(System.currentTimeMillis() + 3_600_000));
        TaskForecast.Task touched = groovyStep(TaskForecaster.of(graph, cas, actionCache, cache));
        assertThat(touched.cached())
                .as("an extra-root source newer than the stamp is not cached")
                .isFalse();
    }

    private static TaskForecast.Task groovyStep(List<TaskForecast.Module> plan) {
        return plan.stream().filter(m -> m.dir().endsWith("a")).findFirst().orElseThrow().steps().stream()
                .filter(s -> s.name().equals("compile-groovy"))
                .findFirst()
                .orElseThrow();
    }

    private static Path workspaceWithModule(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
            group = "t"
            name = "ws"
            version = "0.1.0"
            jdk = 25
            java = 25

            [workspace]
            modules = ["a"]
            """);
        Path mod = Files.createDirectories(tmp.resolve("a"));
        Files.writeString(mod.resolve("jk.toml"), """
            group = "t"
            name = "a"
            version = "0.1.0"
            jdk = 25
            groovy = "5.0.4"

            [build]
            extra-src = ["grails-app/services"]
            """);
        return mod;
    }

    /** Same manifests-sha256 pinning as {@link TaskForecasterGroovyTest} — see its comment. */
    private static void lock(Path tmp) throws Exception {
        String manifestsSha = LockManifestDigest.compute(tmp);
        Files.writeString(tmp.resolve("jk-lock.toml"), """
            version = 1
            generated-by = "test"
            resolution-algorithm = "pubgrub-v1"
            manifests-sha256 = "%s"
            """.formatted(manifestsSha));
    }

    /**
     * Push a source's mtime an hour back so stamp comparisons don't race the wall clock:
     * {@code looksFresh} treats {@code mtime >= stampMillis} as changed, so a source and the stamp
     * that follows it must not be able to share a millisecond.
     */
    private static void agedByAnHour(Path file) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
    }
}
