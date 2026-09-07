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
 * The forecast's compile-groovy block is stamp-only (like Kotlin's): no stamp ⇒ FULL and the
 * module seeds downstream dirtiness; a fresh {@code .gstamp} in the merged classes dir ⇒ CACHED.
 */
class TaskForecasterGroovyTest {

    @Test
    void groovy_module_forecasts_full_then_cached_on_stamp(@TempDir Path tmp) throws Exception {
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
            """);
        Path src = Files.createDirectories(mod.resolve("src"));
        Path foo = src.resolve("Foo.groovy");
        Files.writeString(foo, "class Foo {}");
        agedByAnHour(foo);
        // Workspace lock lives at the root only (not under the module). Must stamp
        // manifests-sha256 or LockFreshness treats the lock as always-stale and the forecast
        // short-circuits to a single "compile-main" step (no compile-groovy).
        String manifestsSha = LockManifestDigest.compute(tmp);
        Files.writeString(tmp.resolve("jk-lock.toml"), """
            version = 1
            generated-by = "test"
            resolution-algorithm = "pubgrub-v1"
            manifests-sha256 = "%s"
            """.formatted(manifestsSha));

        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        List<TaskForecast.Module> plan = TaskForecaster.of(graph, cas, actionCache, cache);
        // TempDir paths may be symlink-normalized by the graph — match by basename.
        TaskForecast.Module a =
                plan.stream().filter(m -> m.dir().endsWith("a")).findFirst().orElseThrow();
        TaskForecast.Task gv = a.steps().stream()
                .filter(s -> s.name().equals("compile-groovy"))
                .findFirst()
                .orElseThrow();
        assertThat(gv.cached()).isFalse();

        // Stamp the merged classes dir (where write-stamp-groovy writes it) — CACHED.
        var layout = BuildLayout.of(mod, JkBuildParser.parse(mod.resolve("jk.toml")));
        FreshnessStamp.write(
                layout.classesDir(), BuildStamps.GROOVY, "compile-groovy", "", List.of(foo), List.of(), 21);
        List<TaskForecast.Module> warm = TaskForecaster.of(graph, cas, actionCache, cache);
        TaskForecast.Task warmGv =
                warm.stream().filter(m -> m.dir().endsWith("a")).findFirst().orElseThrow().steps().stream()
                        .filter(s -> s.name().equals("compile-groovy"))
                        .findFirst()
                        .orElseThrow();
        assertThat(warmGv.cached()).isTrue();

        // The boundary the pinning above keeps the test away from, asserted rather than assumed:
        // a source at or past stampMillis reads as changed, which is what an unpinned source can
        // land on when the forecast and the stamp share a filesystem tick.
        Files.setLastModifiedTime(foo, FileTime.fromMillis(System.currentTimeMillis() + 3_600_000));
        List<TaskForecast.Module> touched = TaskForecaster.of(graph, cas, actionCache, cache);
        TaskForecast.Task touchedGv =
                touched.stream().filter(m -> m.dir().endsWith("a")).findFirst().orElseThrow().steps().stream()
                        .filter(s -> s.name().equals("compile-groovy"))
                        .findFirst()
                        .orElseThrow();
        assertThat(touchedGv.cached())
                .as("a source newer than the stamp is not cached")
                .isFalse();
    }

    /**
     * Push a source's mtime an hour back so stamp comparisons don't race the wall clock:
     * {@code looksFresh} treats {@code mtime >= stampMillis} as changed, so a source and the stamp
     * that follows it must not be able to share a millisecond. Without this the assertion rests on
     * the cold forecast taking longer than one filesystem tick, which under a loaded parallel run
     * it does not reliably do.
     */
    private static void agedByAnHour(Path file) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
    }
}
