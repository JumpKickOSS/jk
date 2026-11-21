// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An annotation processor that reads a variable the module declares in {@code [env]} writes what
 * the value says, build after build: a changed value recompiles instead of restoring the output
 * the old one made, with tests skipped as under {@code jk build -S}, and the forecast keys the
 * compile the way the build does, cached under a value a build ran with and not under a new one.
 */
@Tag("integration")
class EnvProcessorE2eTest {

    private static final String MODE = "META-INF/app/mode";

    @TempDir
    Path tmp;

    @Test
    void a_processor_reading_a_declared_variable_recompiles_when_the_value_changes() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("env-processor-cache");
        lock(ws, cache);
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));

        build(ws, cache, "A");
        assertThat(Files.readString(app.classesDir().resolve(MODE))).isEqualTo("A");

        build(ws, cache, "B");
        assertThat(Files.readString(app.classesDir().resolve(MODE)))
                .as("the value changed, so the compile ran again rather than restoring A's output")
                .isEqualTo("B");
        assertThat(compileCached(ws, cache, "B"))
                .as("forecast under the value it ran with")
                .hasValue(true);
        assertThat(compileCached(ws, cache, "C"))
                .as("forecast under a value no build ran with")
                .hasValue(false);

        build(ws, cache, "A");
        assertThat(Files.readString(app.classesDir().resolve(MODE)))
                .as("back to A: A's compile is restored")
                .isEqualTo("A");
    }

    private static void build(Path ws, Path cache, String mode) throws Exception {
        WorkspaceResult result = RequestShell.where(
                Map.of("GEN_MODE", mode),
                () -> WorkspaceExecute.buildWorkspace(
                        new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                        new WorkspaceBuildListener() {}));
        assertThat(result.errors()).as("errors, build under " + mode).isEmpty();
        assertThat(result.success()).as("success, build under " + mode).isTrue();
    }

    private static Optional<Boolean> compileCached(Path ws, Path cache, String mode) throws Exception {
        return RequestShell.where(Map.of("GEN_MODE", mode), () -> {
            BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
            assertThat(graph.hasErrors()).isFalse();
            ActionCache actionCache =
                    new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
            List<TaskForecast.Module> plan = ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
            TaskForecast.Module app = plan.stream()
                    .filter(m -> m.dir().getFileName().toString().equals("app"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the forecast has no app module: " + plan));
            Optional<Boolean> cached = app.steps().stream()
                    .filter(s -> s.name().equals(TaskNames.COMPILE_JAVA))
                    .findFirst()
                    .map(TaskForecast.Task::cached);
            // A forecast that threw degrades to a module with no steps and a reason; name it, so a
            // failure under load says why the compile is missing.
            if (cached.isEmpty()) {
                throw new AssertionError("no " + TaskNames.COMPILE_JAVA + " in app's forecast under " + mode
                        + ": reason " + app.reason() + ", steps "
                        + app.steps().stream().map(TaskForecast.Task::name).toList());
            }
            return cached;
        });
    }

    private static void lock(Path ws, Path cache) throws Exception {
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String module : List.of("proc", "app")) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(module + "/jk-lock.toml"));
        }
    }

    /** A processor module writing {@code GEN_MODE} into the class output, and an app that declares it. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["proc", "app"]
                """);
        Path proc = Files.createDirectories(ws.resolve("proc"));
        Files.writeString(proc.resolve("jk.toml"), module("proc", ""));
        Path procSrc = Files.createDirectories(proc.resolve("src/proc"));
        Files.writeString(procSrc.resolve("ModeProc.java"), """
                package proc;

                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import javax.tools.StandardLocation;

                /** Writes the GEN_MODE it was compiled under into the class output. */
                @SupportedAnnotationTypes("*")
                public final class ModeProc extends AbstractProcessor {
                    @Override
                    public SourceVersion getSupportedSourceVersion() {
                        return SourceVersion.latestSupported();
                    }

                    @Override
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (!round.processingOver()) return false;
                        try (Writer w = processingEnv.getFiler()
                                .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/app/mode")
                                .openWriter()) {
                            w.write(String.valueOf(System.getenv("GEN_MODE")));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return false;
                    }
                }
                """);
        Path procServices = Files.createDirectories(proc.resolve("resources/META-INF/services"));
        Files.writeString(procServices.resolve("javax.annotation.processing.Processor"), "proc.ModeProc\n");

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), module("app", """

                [processor-dependencies]
                proc = { workspace = true }

                [env]
                optional = ["GEN_MODE"]
                """));
        Path appSrc = Files.createDirectories(app.resolve("src/app"));
        Files.writeString(appSrc.resolve("Widget.java"), "package app;\n\npublic final class Widget {}\n");
        return ws;
    }

    private static String module(String name, String tables) {
        return """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name) + tables;
    }
}
