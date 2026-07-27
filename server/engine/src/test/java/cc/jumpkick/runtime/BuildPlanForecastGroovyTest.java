// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast's compile-groovy block is stamp-only (like Kotlin's): no stamp ⇒ FULL and the
 * module seeds downstream dirtiness; a fresh {@code .gstamp} in the merged classes dir ⇒ CACHED.
 */
class BuildPlanForecastGroovyTest {

    @Test
    void groovy_module_forecasts_full_then_cached_on_stamp(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["a"]
                """);
        Path mod = Files.createDirectories(tmp.resolve("a"));
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "a"
                version = "0.1.0"
                jdk = 21
                groovy = "5.0.4"
                layout = "simple"
                """);
        Path src = Files.createDirectories(mod.resolve("src"));
        Path foo = src.resolve("Foo.groovy");
        Files.writeString(foo, "class Foo {}");
        Files.writeString(mod.resolve("jk.lock"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);

        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        List<BuildPlan.Module> plan = BuildPlanForecast.of(graph, cas, actionCache, cache);
        // TempDir paths may be symlink-normalized by the graph — match by basename.
        BuildPlan.Module a = plan.stream()
                .filter(m -> m.dir().endsWith("a"))
                .findFirst()
                .orElseThrow();
        BuildPlan.Step gv = a.steps().stream()
                .filter(s -> s.name().equals("compile-groovy"))
                .findFirst()
                .orElseThrow();
        assertThat(gv.cached()).isFalse();

        // Stamp the merged classes dir (where write-stamp-groovy writes it) — CACHED.
        var layout = cc.jumpkick.layout.BuildLayout.of(mod, JkBuildParser.parse(mod.resolve("jk.toml")));
        FreshnessStamp.write(
                layout.classesDir(),
                FreshnessStamp.GROOVY_STAMP,
                "compile-groovy",
                "",
                List.of(foo),
                List.of(),
                21);
        List<BuildPlan.Module> warm = BuildPlanForecast.of(graph, cas, actionCache, cache);
        BuildPlan.Step warmGv = warm.stream()
                .filter(m -> m.dir().endsWith("a"))
                .findFirst()
                .orElseThrow()
                .steps()
                .stream()
                .filter(s -> s.name().equals("compile-groovy"))
                .findFirst()
                .orElseThrow();
        assertThat(warmGv.cached()).isTrue();
    }
}
