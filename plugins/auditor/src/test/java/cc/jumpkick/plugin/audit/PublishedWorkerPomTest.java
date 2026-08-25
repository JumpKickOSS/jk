// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.model.JkVersion;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

/**
 * A worker is only as good as the POM its launch classpath is rebuilt from. Nothing in this tree
 * read a published worker POM, and for that whole time {@code jk.plugin-conventions} carried a
 * {@code pom.withXml} artifactId remap that never matched a node — {@code asNode()} parses
 * namespace-aware, so {@code node.name()} is a {@code QName} printing {@code
 * {http://maven.apache.org/POM/4.0.0}artifactId} and the comparison against the bare string was
 * always false (JK-2497). Code that looks right and silently does nothing is exactly what a test
 * that reads the artifact catches and an inspection does not.
 *
 * <p>The repository is the one {@code stageWorkerRepo} writes — byte for byte what {@code
 * installLocal} copies into {@code store/repos/jk-local} and what {@code
 * scripts/publish-maven-repo.sh} uploads to the official repo. Asserting on POM text would prove
 * the string changed; this resolves the closure the way {@code PomRuntimeClasspath} does at worker
 * launch and then compiles against the jars that came back.
 *
 * <p>The worker POM is deliberately <em>flat</em>: {@code writeWorkerPom} resolves the whole
 * runtime classpath itself and names every coordinate, so a consumer needs one POM and no
 * recursion. The contract this asserts is therefore "every coordinate the POM names has a jar in
 * the repository", not "every dependency has a POM of its own".
 */
class PublishedWorkerPomTest {

    /** Maven root of the staged worker repository. Set by {@code :auditor:test}. */
    private static final String REPO_PROPERTY = "jk.worker.repo";

    /** The Gradle project name, so the published artifactId is derived here and not re-typed. */
    private static final String PROJECT_PROPERTY = "jk.worker.project";

    private static final String GROUP = "cc.jumpkick";

    /**
     * One class implementing {@code BuildPlugin} — what a worker jar's entry point is — touching a
     * type from each first-party rung of the closure: the SPI ({@code jk-plugin-sdk}), the codec
     * ({@code jk-host}), the domain ({@code jk-api}) and the config layer ({@code jk-core}). Those
     * four are separate artifacts; only the POM's dependency list connects them, which is the part
     * a wrong artifactId severs.
     */
    private static final String CONSUMER_SOURCE = """
            package consumer;

            import cc.jumpkick.jsonl.Jsonl;
            import cc.jumpkick.lock.Lockfile;
            import cc.jumpkick.model.Scope;
            import cc.jumpkick.plugin.build.BuildPlugin;
            import cc.jumpkick.plugin.build.BuildPluginContext;

            public final class ConsumerWorker implements BuildPlugin {
                @Override
                public void register(BuildPluginContext ctx) {
                    Class<?>[] closure = {Lockfile.class, Scope.class};
                    System.out.println(Jsonl.quote(ctx.project().name() + closure.length));
                }
            }
            """;

    @Test
    void the_published_worker_pom_names_coordinates_the_repository_can_serve(@TempDir Path work) throws Exception {
        Path repo = stagedRepo();
        String artifact = "jk-" + System.getProperty(PROJECT_PROPERTY);
        String version = JkVersion.VERSION;

        Path pom = repo.resolve(GROUP.replace('.', '/'))
                .resolve(artifact)
                .resolve(version)
                .resolve(artifact + "-" + version + ".pom");
        assertThat(pom)
                .as(
                        "`writeWorkerPom` publishes %s:%s:%s — the POM a worker launch rebuilds -cp from",
                        GROUP, artifact, version)
                .isRegularFile();

        Element project = DomXml.parse(pom).getDocumentElement();
        assertThat(DomXml.childText(project, "artifactId"))
                .as(
                        "the worker publishes under its jk- artifactId, not its Gradle project name"
                                + " (%s) — %s is what jk.toml, PluginJar and the official repo all"
                                + " name",
                        System.getProperty(PROJECT_PROPERTY), artifact)
                .isEqualTo(artifact);
        assertThat(DomXml.childText(project, "groupId")).isEqualTo(GROUP);
        assertThat(DomXml.childText(project, "version")).isEqualTo(version);

        Map<String, Path> jars = new LinkedHashMap<>();
        List<String> broken = new ArrayList<>();
        List<Element> dependencies = DomXml.childElements(DomXml.childElement(project, "dependencies"), "dependency");
        assertThat(dependencies)
                .as("a flat worker POM names its whole runtime closure; an empty list would let"
                        + " every assertion below pass having checked nothing")
                .hasSizeGreaterThanOrEqualTo(4);

        for (Element dependency : dependencies) {
            String g = DomXml.childText(dependency, "groupId");
            String a = DomXml.childText(dependency, "artifactId");
            String v = DomXml.childText(dependency, "version");
            String coordinate = g + ":" + a + ":" + v;
            if (g == null || g.isBlank() || a == null || a.isBlank() || v == null || v.isBlank()) {
                broken.add(coordinate + " — a coordinate field is empty");
                continue;
            }
            // The two shapes Gradle renders from its own defaults when a module sets no
            // coordinates: `jk` is rootProject.name, `unspecified` is Project.DEFAULT_VERSION.
            if ("jk".equals(g) || g.startsWith("jk.")) {
                broken.add(coordinate + " — groupId is the rootProject.name fallback");
            }
            if ("unspecified".equals(v)) {
                broken.add(coordinate + " — version is Gradle's unspecified default");
            }
            if (GROUP.equals(g) && !a.startsWith("jk-")) {
                broken.add(coordinate + " — first-party artifacts publish as jk-<module>");
            }
            Path dir = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
            Path jar = dir.resolve(a + "-" + v + ".jar");
            if (Files.isRegularFile(jar)) {
                jars.put(g + ":" + a, jar);
            } else {
                broken.add(coordinate + " — no " + repo.relativize(jar));
            }
        }

        assertThat(broken)
                .as(
                        "every coordinate %s names must be one this build stages a jar for; a worker"
                                + " whose POM names an artifact nobody publishes cannot start",
                        pom.getFileName())
                .isEmpty();
        assertThat(jars.keySet())
                .as("the first-party rungs of the closure, from the worker POM alone")
                .contains(GROUP + ":jk-core", GROUP + ":jk-plugin-sdk", GROUP + ":jk-host", GROUP + ":jk-api");

        compileAgainst(jars.values(), work);
    }

    /** The staged repository, or a failure that says which task fills it. */
    private static Path stagedRepo() {
        String repoPath = System.getProperty(REPO_PROPERTY);
        assertThat(repoPath)
                .as(
                        "-D%s must point at the staged Maven repository; :auditor:test sets it and"
                                + " depends on stageWorkerRepo, which fills it",
                        REPO_PROPERTY)
                .isNotNull();
        Path repo = Path.of(repoPath);
        assertThat(repo).isDirectory();
        return repo;
    }

    /**
     * Compile {@link #CONSUMER_SOURCE} against exactly the jars the POM resolved to — no Gradle
     * classpath, no {@code project(...)} shortcut. A coordinate that names the wrong artifact
     * resolves to the wrong jar and this is where that stops being a string comparison.
     */
    private static void compileAgainst(Iterable<Path> jars, Path work) throws IOException {
        Path source = work.resolve("consumer/ConsumerWorker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, CONSUMER_SOURCE);
        Path classes = Files.createDirectories(work.resolve("classes"));

        List<Path> entries = new ArrayList<>();
        jars.forEach(entries::add);
        String classpath = entries.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));

        StringWriter diagnostics = new StringWriter();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        boolean compiled;
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(source);
            List<String> options = List.of("-classpath", classpath, "-d", classes.toString(), "-proc:none");
            compiled = javac.getTask(diagnostics, files, null, options, null, units)
                    .call();
        }
        assertThat(compiled)
                .as(
                        "a BuildPlugin implementation compiles against the resolved closure alone (classpath %s):%n%s",
                        classpath, diagnostics)
                .isTrue();
        assertThat(classes.resolve("consumer/ConsumerWorker.class")).exists();
    }
}
