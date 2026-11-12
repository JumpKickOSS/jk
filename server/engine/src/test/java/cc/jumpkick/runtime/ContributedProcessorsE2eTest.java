// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.TestCaches;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module that declares its own annotation processor in {@code [processor-dependencies]}, under a
 * plugin that contributes another: {@code jk lock} pins the plugin's processor closure, both
 * processors run over the main and the test sources, and the forecast after the build prices both
 * compiles cached — it keys the {@code -processorpath} the build handed javac.
 *
 * <p>Each processor generates {@code <Type><Annotation>} for every type its annotation marks, and
 * the sources reference what both generate, so a processor that does not run fails the compile.
 */
@Tag("integration")
class ContributedProcessorsE2eTest {

    private static final String GROUP = "com.example.apt";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @TempDir
    Path tmp;

    private final String version = MavenStub.freshVersion();

    private final PluginDescriptor fixture = PluginDescriptors.parse("""
            [plugin]
            id = "apt-e2e-fixture"
            table = "apt-e2e-fixture"

            [schema]
            version = { type = "string", required = true }

            [[contribute.processor-dependency]]
            coordinate = "com.example.apt:plugin-apt:${config.version}"
            """, "apt-e2e-fixture.toml");

    @BeforeEach
    void publish() throws IOException {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        PluginTableRegistry.putBuiltIn(fixture, null);
        MavenStub repo = new MavenStub(http);
        publish(repo, "annotations", annotationsJar(), "");
        publish(repo, "user-apt", processorJar("user", "UserGen"), "");
        // The plugin's processor brings a helper of its own: the closure, not the root alone, is pinned.
        publish(repo, "plugin-helper", MavenStub.EMPTY_JAR, "");
        publish(repo, "plugin-apt", processorJar("plugin", "PluginGen"), "plugin-helper");
    }

    @AfterEach
    void uninstall() {
        PluginTableRegistry.unregister(fixture);
    }

    @Test
    void the_declared_and_the_contributed_processor_both_run_over_main_and_test() throws Exception {
        Path cache = TestCaches.dir("contributed-processors-e2e-cache");
        Path project = project();
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).as("lock").isEmpty();
        assertThat(lock.success()).as("lock").isTrue();

        BuildPlanResult result =
                BuildPlanner.fullPlan(TestInputs.of(project, cache)).run();
        assertThat(result.errors()).as("build").isEmpty();
        assertThat(result.success()).as("build").isTrue();

        Path target = project.resolve("target");
        assertThat(target.resolve("classes/com/example/OneUserGen.class")).isRegularFile();
        assertThat(target.resolve("classes/com/example/OnePluginGen.class")).isRegularFile();
        assertThat(target.resolve("test-classes/com/example/OneTestUserGen.class"))
                .isRegularFile();
        assertThat(target.resolve("test-classes/com/example/OneTestPluginGen.class"))
                .isRegularFile();

        List<TaskForecast.Task> steps = forecast(project, cache).steps();
        for (String step : List.of(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_TEST)) {
            TaskForecast.Task compile = steps.stream()
                    .filter(s -> step.equals(s.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(compile.cached())
                    .as("%s after a build that ran it: %s", step, compile.text())
                    .isTrue();
        }
    }

    private Path project() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("svc"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "svc"
                version = "1.0.0"
                java    = 25

                [apt-e2e-fixture]
                version = "%1$s"

                [provided-dependencies]
                annotations = { group = "%2$s", name = "annotations", version = "%1$s" }

                [processor-dependencies]
                user-apt = { group = "%2$s", name = "user-apt", version = "%1$s" }

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [repositories.stub]
                url = "%3$s"
                groups = ["%2$s"]
                """.formatted(version, GROUP, http.baseUrl()));
        write(lib.resolve("src/main/java/com/example/One.java"), """
                package com.example;

                @user.UserGen
                @plugin.PluginGen
                public final class One {
                    static final Object USER = new OneUserGen();
                    static final Object PLUGIN = new OnePluginGen();

                    private One() {}
                }
                """);
        write(lib.resolve("src/test/java/com/example/OneTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                @user.UserGen
                @plugin.PluginGen
                class OneTest {
                    @Test
                    void bothProcessorsRan() {
                        assertNotNull(new OneTestUserGen());
                        assertNotNull(new OneTestPluginGen());
                    }
                }
                """);
        return lib;
    }

    private static TaskForecast.Module forecast(Path project, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(project, JkBuildParser.parse(project.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return ForecastWalk.of(graph, JkStores.storeCas(), actionCache, cache, false).stream()
                .filter(m -> m.dir().getFileName().toString().equals("svc"))
                .findFirst()
                .orElseThrow();
    }

    // ---- the stub repository -----------------------------------------------------------------

    private void publish(MavenStub repo, String artifact, byte[] jar, String dependsOn) {
        String deps = dependsOn.isEmpty()
                ? ""
                : "<dependencies><dependency><groupId>" + GROUP + "</groupId><artifactId>" + dependsOn
                        + "</artifactId><version>" + version + "</version></dependency></dependencies>";
        repo.metadata(GROUP, artifact, version);
        repo.pomOnly(GROUP, artifact, version, """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>%s</project>
                """.formatted(GROUP, artifact, version, deps));
        repo.bytes(MavenStub.path(GROUP, artifact, version, ".jar"), jar);
    }

    /** {@code @user.UserGen} and {@code @plugin.PluginGen}, source-retained type annotations. */
    private byte[] annotationsJar() throws IOException {
        Path out = compile(
                "annotations",
                List.of(
                        "package user; public @interface UserGen {}",
                        "package plugin; public @interface PluginGen {}"));
        return jar(out, null);
    }

    /**
     * A processor that emits {@code <Type><annotation>} for each type marked {@code @<pkg>.<annotation>},
     * finding the annotation by name, so the jar carries no annotation class of its own.
     */
    private byte[] processorJar(String pkg, String annotation) throws IOException {
        String source = """
                package $PKG;
                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.Element;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("$PKG.$ANN")
                public class $ANNProcessor extends AbstractProcessor {
                    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        for (TypeElement annotation : annotations) {
                            for (Element e : round.getElementsAnnotatedWith(annotation)) {
                                TypeElement type = (TypeElement) e;
                                String p = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
                                String simple = type.getSimpleName() + "$ANN";
                                try (Writer w = processingEnv.getFiler().createSourceFile(p + "." + simple, type).openWriter()) {
                                    w.write("package " + p + "; public final class " + simple + " {}");
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            }
                        }
                        return false;
                    }
                }
                """.replace("$PKG", pkg).replace("$ANN", annotation);
        Path out = compile(pkg + "-apt", List.of(source));
        return jar(out, pkg + "." + annotation + "Processor");
    }

    private Path compile(String name, List<String> sources) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("fixture-src").resolve(name));
        Path out = Files.createDirectories(tmp.resolve("fixture-out").resolve(name));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
        for (String source : sources) {
            // javac wants a public type in a file of its name.
            String type = source.replaceAll("(?s).*public (?:final )?(?:class|@interface) (\\w+).*", "$1");
            Path file = Files.createDirectories(src.resolve(type)).resolve(type + ".java");
            Files.writeString(file, source);
            args.add(file.toString());
        }
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("fixture javac failed for " + name + ", rc=" + rc);
        return out;
    }

    /** {@code classes} as a jar, registering {@code processor} as the service when it is not null. */
    private static byte[] jar(Path classes, @Nullable String processor) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes);
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(
                        new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
            if (processor != null) {
                jar.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));
                jar.write((processor + "\n").getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
