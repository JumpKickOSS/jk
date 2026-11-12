// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.task.ActionCache;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * A KSP module the way the slow KSP tests drive one: a processor compiled here against jk's KSP2
 * closure and published to a {@code file://} repository, a project locked against it, its builds,
 * and the forecast {@code jk explain} reads of it.
 */
final class KspProjects {

    private KspProjects() {}

    /**
     * {@code source}, the Java of processor provider {@code provider}, compiled against the KSP2
     * closure, jarred with its service entry and published to {@code repo} as {@code
     * com.example:<artifact>:<version>}; the repository's metadata lists every version published.
     */
    static void publishProcessor(Path repo, Path work, String artifact, String version, String provider, String source)
            throws Exception {
        JkBuild any = JkBuildParser.parse("""
                name    = "any"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Cas cas = JkStores.storeCas();
        List<Path> kspClasspath = KspResolver.resolveClasspath(
                RepoGroupBuilder.buildFor(any, null, cas), cas, KspResolver.DEFAULT_VERSION);
        Path src = work.resolve("src/" + provider.replace('.', '/') + ".java");
        write(src, source);
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-d",
                classes.toString(),
                "-cp",
                kspClasspath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
                src.toString()));
        int exit =
                requireNonNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, args.toArray(String[]::new));
        assertThat(exit).as("the processor compiles against the KSP API").isZero();

        Path artifactDir = repo.resolve("com/example/" + artifact);
        Path vDir = Files.createDirectories(artifactDir.resolve(version));
        try (OutputStream file = Files.newOutputStream(vDir.resolve(artifact + "-" + version + ".jar"));
                JarOutputStream out = new JarOutputStream(file)) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path c : walk.filter(Files::isRegularFile).sorted().toList()) {
                    out.putNextEntry(
                            new JarEntry(classes.relativize(c).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(c));
                    out.closeEntry();
                }
            }
            out.putNextEntry(
                    new JarEntry("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"));
            out.write((provider + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        describe(repo, artifact, version);
    }

    /**
     * An empty jar published to {@code repo} as {@code com.example:<artifact>:<version>}: a
     * processor-path entry that registers no KSP provider, so the round has none to run.
     */
    static void publishPlainJar(Path repo, String artifact, String version) throws IOException {
        Path vDir =
                Files.createDirectories(repo.resolve("com/example/" + artifact).resolve(version));
        try (OutputStream file = Files.newOutputStream(vDir.resolve(artifact + "-" + version + ".jar"));
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("plain/README.txt"));
            out.write("no processor here\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        describe(repo, artifact, version);
    }

    /** The pom of one published version and the metadata listing every version published. */
    private static void describe(Path repo, String artifact, String version) throws IOException {
        Path artifactDir = repo.resolve("com/example/" + artifact);
        Files.writeString(artifactDir.resolve(version).resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(
                        artifact, version));
        List<String> versions;
        try (Stream<Path> dirs = Files.list(artifactDir)) {
            versions = dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        }
        String listed =
                versions.stream().map(v -> "<version>" + v + "</version>").collect(Collectors.joining());
        Files.writeString(artifactDir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions>%s</versions>
                  </versioning>
                </metadata>
                """.formatted(
                        artifact, versions.getLast(), versions.getLast(), listed));
    }

    /** Write {@code manifest} and lock it; the module's layout. */
    static BuildLayout lock(Path project, String manifest, Path cache) throws Exception {
        Files.writeString(project.resolve("jk.toml"), manifest);
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();
        return BuildLayout.of(project, build);
    }

    /** What the forecast prices each step of the module at, skipping tests when {@code skipTests}. */
    static Map<String, TaskForecast.Task> forecast(Path project, Path cache, boolean skipTests) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(project, JkBuildParser.parse(project.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        // As `jk explain` reads them: artifacts from the store, records from the cache.
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        List<TaskForecast.Module> modules = ForecastWalk.of(graph, JkStores.storeCas(), actionCache, cache, skipTests);
        assertThat(modules).hasSize(1);
        Map<String, TaskForecast.Task> steps = new LinkedHashMap<>();
        for (TaskForecast.Task t : modules.getFirst().steps()) steps.put(t.name(), t);
        return steps;
    }

    static Set<String> jarEntries(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(JarEntry::getName).collect(Collectors.toSet());
        }
    }

    static BuildPlanResult build(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(inputs(project, cache, false)).run();
    }

    static BuildPlanResult buildSkippingTests(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(inputs(project, cache, true)).run();
    }

    private static BuildPlanner.Inputs inputs(Path project, Path cache, boolean skipTests) {
        return TestInputs.of(project, cache).withSkipTests(skipTests);
    }

    static BuildPlanResult.StepReport step(BuildPlanResult result, String name) {
        return result.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " step in "
                        + result.steps().stream()
                                .map(BuildPlanResult.StepReport::name)
                                .toList()));
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, text);
    }
}
