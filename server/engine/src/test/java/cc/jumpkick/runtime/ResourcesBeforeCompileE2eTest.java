// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Main resources are copied into the classes tree before the compile, as Maven's {@code
 * process-resources} runs before {@code compile}: an annotation processor reads a static resource
 * through the Filer and writes it back merged, as Helidon's codegen merges a module's {@code
 * META-INF/helidon/manifest}, and the merged file is the one the jar carries. A build with nothing
 * changed keeps it without copying, a resource edit or deletion re-merges, an unrelated source edit
 * keeps the merge, and a classes tree restored from the compile cache has it back.
 */
@Tag("integration")
class ResourcesBeforeCompileE2eTest {

    private static final String MANIFEST = "META-INF/app/manifest";

    @TempDir
    Path tmp;

    @Test
    void a_processor_merges_into_a_copied_resource_and_the_jar_keeps_the_merge() throws Exception {
        Path ws = workspace(tmp);
        Path cache = TestCaches.dir("resources-before-compile-cache");
        lock(ws, cache);
        BuildLayout app = BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));
        Path resource = ws.resolve("app/resources/" + MANIFEST);

        build(ws, cache, "first");
        assertThat(Files.readString(app.classesDir().resolve(MANIFEST)))
                .as("the processor read the copied resource and wrote the merge over it")
                .isEqualTo("app.Widget\nstatic.Row\n");
        assertThat(jarEntry(app.mainJar(), MANIFEST)).isEqualTo("app.Widget\nstatic.Row\n");

        // Planned although nothing changed, so its steps report.
        Steps second = build(ws, cache, "second", Set.of(ws.resolve("app")));
        assertThat(second.status("app", TaskNames.COPY_RESOURCES))
                .as("a copy the processor rewrote is not drift while its resource is unchanged")
                .isEqualTo(TaskStatus.SKIPPED);
        assertThat(jarEntry(app.mainJar(), MANIFEST)).isEqualTo("app.Widget\nstatic.Row\n");
        assertThat(forecast(ws, cache, "app", TaskNames.COPY_RESOURCES)).isEmpty();
        assertThat(forecast(ws, cache, "app", TaskNames.COMPILE_JAVA).map(TaskForecast.Task::cached))
                .hasValue(true);

        Files.writeString(resource, "static.Other\n");
        build(ws, cache, "resource edited");
        assertThat(jarEntry(app.mainJar(), MANIFEST))
                .as("a resource edit recompiles, and the processor merges into the new bytes")
                .isEqualTo("app.Widget\nstatic.Other\n");

        Files.writeString(ws.resolve("app/src/app/Plain.java"), "package app;\n\npublic final class Plain {}\n");
        build(ws, cache, "unrelated source");
        assertThat(jarEntry(app.mainJar(), MANIFEST)).isEqualTo("app.Widget\nstatic.Other\n");

        PathUtil.deleteRecursively(app.classesDir());
        build(ws, cache, "classes wiped");
        assertThat(Files.readString(app.classesDir().resolve(MANIFEST)))
                .as("the compile restored from the cache lays the processor's file down again")
                .isEqualTo("app.Widget\nstatic.Other\n");

        Files.delete(resource);
        build(ws, cache, "resource deleted");
        assertThat(jarEntry(app.mainJar(), MANIFEST)).isEqualTo("app.Widget\n");
    }

    private static @Nullable String jarEntry(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            return entry == null ? null : new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Optional<TaskForecast.Task> forecast(Path ws, Path cache, String module, String step)
            throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        List<TaskForecast.Module> plan = ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
        return plan.stream()
                .filter(x -> x.dir().getFileName().toString().equals(module))
                .flatMap(m -> m.steps().stream())
                .filter(s -> s.name().equals(step))
                .findFirst();
    }

    private static Steps build(Path ws, Path cache, String what) {
        return build(ws, cache, what, null);
    }

    private static Steps build(Path ws, Path cache, String what, @Nullable Set<Path> dirty) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, dirty, false, false), steps);
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
        return steps;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String module : List.of("proc", "app")) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(module + "/jk-lock.toml"));
        }
    }

    /** Per module and step: final status. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    statusByModuleStep.put(name + "/" + step, status);
                }
            };
        }

        @Nullable
        TaskStatus status(String module, String step) {
            return statusByModuleStep.get(module + "/" + step);
        }
    }

    /**
     * A processor module whose processor merges the {@code @Reg} types into {@code
     * META-INF/app/manifest}, read from the class output first, with no originating elements — as
     * Helidon writes its manifest — and an app whose resources ship a static manifest.
     */
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
        Files.writeString(procSrc.resolve("Reg.java"), """
                package proc;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                /** Lists a type in the manifest. */
                @Retention(RetentionPolicy.SOURCE)
                @Target(ElementType.TYPE)
                public @interface Reg {}
                """);
        Files.writeString(procSrc.resolve("MergeProc.java"), """
                package proc;

                import java.io.BufferedReader;
                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.io.Writer;
                import java.util.Set;
                import java.util.TreeSet;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.Element;
                import javax.lang.model.element.TypeElement;
                import javax.tools.FileObject;
                import javax.tools.StandardLocation;

                /** Merges the annotated types into the manifest the class output already holds. */
                @SupportedAnnotationTypes("proc.Reg")
                public final class MergeProc extends AbstractProcessor {
                    private final Set<String> rows = new TreeSet<>();

                    @Override
                    public SourceVersion getSupportedSourceVersion() {
                        return SourceVersion.latestSupported();
                    }

                    @Override
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (Element e : round.getElementsAnnotatedWith(Reg.class)) {
                            rows.add(((TypeElement) e).getQualifiedName().toString());
                        }
                        if (!round.processingOver() || rows.isEmpty()) return false;
                        try {
                            try {
                                FileObject in = processingEnv.getFiler()
                                        .getResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/app/manifest");
                                try (BufferedReader reader = new BufferedReader(in.openReader(true))) {
                                    for (String line; (line = reader.readLine()) != null; ) {
                                        if (!line.isBlank()) rows.add(line.strip());
                                    }
                                }
                            } catch (IOException none) {
                                // no static manifest to merge into
                            }
                            FileObject out = processingEnv.getFiler()
                                    .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/app/manifest");
                            try (Writer w = out.openWriter()) {
                                for (String row : rows) w.write(row + "\\n");
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return false;
                    }
                }
                """);
        Path procServices = Files.createDirectories(proc.resolve("resources/META-INF/services"));
        Files.writeString(procServices.resolve("javax.annotation.processing.Processor"), "proc.MergeProc\n");

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), module("app", """

                [dependencies]
                proc = { workspace = true }

                [processor-dependencies]
                proc = { workspace = true }
                """));
        Path appSrc = Files.createDirectories(app.resolve("src/app"));
        Files.writeString(appSrc.resolve("Widget.java"), """
                package app;

                /** Listed in the manifest. */
                @proc.Reg
                public final class Widget {}
                """);
        Path appRes = Files.createDirectories(app.resolve("resources/META-INF/app"));
        Files.writeString(appRes.resolve("manifest"), "static.Row\n");
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
