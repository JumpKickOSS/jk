// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A module suite whose guard reads {@code Model} alone keys its lane on the manifests and the lock. */
class GuardSuiteModelKeyTest {

    private static final String SUITE = """
            package fx;

            import cc.jumpkick.guard.api.Guard;
            import cc.jumpkick.guard.api.GuardSuite;
            import cc.jumpkick.guard.api.Model;
            import cc.jumpkick.guard.api.Violations;

            @GuardSuite
            final class ReadsModel {
                @Guard(id = "reads-model", why = "a verdict on the manifests moves with them")
                void readsModel(Model m, Violations v) {
                    v.population(m.modules().size());
                }
            }
            """;

    @Test
    void a_manifest_edit_turns_a_cached_model_suite_lane_into_a_run(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Path manifest = root.resolve("jk.toml");
        Files.writeString(manifest, "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.writeString(root.resolve("src/main/java/app/Main.java"), "package app; public class Main {}\n");
        Files.createDirectories(root.resolve("src/guard/java/fx"));
        Files.writeString(root.resolve("src/guard/java/fx/ReadsModel.java"), SUITE);
        JkBuild build = JkBuildParser.parse(manifest);
        BuildLayout layout = BuildLayout.of(root, build);

        compile(tmp.resolve("main-src"), layout.classesDir(), "app", "Main", "package app; public class Main {}");
        FactsIndexing.ensure(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
        compile(tmp.resolve("suite-src"), layout.guardClassesDir(), "fx", "ReadsModel", SUITE);
        FactsIndexing.ensure(layout.guardClassesDir(), FactsIndexing.indexPath(layout.buildDir(), "guard"));

        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        String key = Objects.requireNonNull(
                GuardKeys.probeModuleLane(root, root, layout, load, true).key(), "the lane's key");
        cache.storeVerdict(ActionKey.qualifiedTaskId(TaskNames.GUARD, root), key, Map.of());
        assertThat(lane(root, layout, cache).status()).isEqualTo(TaskForecast.Status.CACHED);

        // A dependency added to the manifest leaves every class file as it was.
        Files.writeString(
                manifest, Files.readString(manifest) + "\n[dependencies]\nbanned = \"org.example:banned:1.0\"\n");

        assertThat(GuardKeys.probeModuleLane(root, root, layout, load, true).key())
                .isNotEqualTo(key);
        assertThat(lane(root, layout, cache).status()).isEqualTo(TaskForecast.Status.RUN);
    }

    private static TaskForecast.Task lane(Path root, BuildLayout layout, ActionCache cache) {
        return GuardKeys.forecastModuleLane(root, layout, cache, false, false).orElseThrow();
    }

    private static void compile(Path src, Path out, String pkg, String simpleName, String source) throws Exception {
        Path file = Files.createDirectories(src.resolve(pkg)).resolve(simpleName + ".java");
        Files.writeString(file, source);
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(
                null,
                null,
                null,
                "-d",
                out.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "-proc:none",
                file.toString());
        assertThat(rc).as("javac").isZero();
    }
}
