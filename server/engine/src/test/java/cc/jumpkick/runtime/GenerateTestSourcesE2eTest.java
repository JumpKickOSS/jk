// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hand-written {@code [generate.<name>]} entry through the engine: the tool and the jar it
 * unpacks come from a {@code file://} repository beside Central (JUnit), the entry contributes
 * test sources, and the module's test compiles and runs against what the tool wrote while the main
 * compile never sees it. A second build finds the generate step cached.
 *
 * <p>Network test (Maven Central for JUnit).
 */
@Tag("network")
class GenerateTestSourcesE2eTest {

    @Test
    void an_unpack_entry_generates_test_sources_the_test_compile_reads(@TempDir Path tmp) throws Exception {
        workerJarFromWorkspace(PluginJar.GENERATOR, "plugins/generator");
        Path repo = tmp.resolve("repo");
        toolArtifact(repo, "com.example", "gen-tool", "1.0");
        protosArtifact(repo, "com.example", "protos", "1.0");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                java = 25

                [repositories.local]
                url = "%s"

                [generate.gen]
                tool   = "com.example:gen-tool:1.0"
                unpack = "com.example:protos:1.0"
                args   = ["${unpacked}", "${out}"]
                contributes = "test-sources"

                [test-dependencies]
                junit-jupiter = "6.1.3"
                """.formatted(repo.toUri()));
        write(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    private Main() {}
                }
                """);
        write(project.resolve("src/test/java/com/example/UsesGeneratedTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class UsesGeneratedTest {
                    @Test
                    void the_generated_class_carries_the_jars_text() {
                        assertEquals("from-the-jar", gen.Hello.NAME);
                    }
                }
                """);
        Path cache = tmp.resolve("cache");
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(step(first, "generate-gen").status()).isEqualTo(TaskStatus.SUCCESS);
        BuildLayout layout = BuildLayout.of(project, build);
        assertThat(layout.testClassesDir().resolve("com/example/UsesGeneratedTest.class"))
                .as("the test compiled against the generated class")
                .isRegularFile();
        assertThat(layout.testClassesDir().resolve("gen/Hello.class"))
                .as("the generated source compiled with the tests")
                .isRegularFile();
        assertThat(layout.classesDir().resolve("gen/Hello.class"))
                .as("the main compile never saw the test-sources contribution")
                .doesNotExist();

        BuildPlanResult second = build(project, cache);
        assertThat(second.success()).isTrue();
        assertThat(step(second, "generate-gen").status())
                .as("an unchanged entry is a cache hit")
                .isEqualTo(TaskStatus.SKIPPED);
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }

    private static BuildPlanResult.StepReport step(BuildPlanResult result, String name) {
        return result.steps().stream()
                .filter(s -> s.name().contains(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " step in "
                        + result.steps().stream()
                                .map(BuildPlanResult.StepReport::name)
                                .toList()));
    }

    private static void workerJarFromWorkspace(PluginJar worker, String module) throws IOException {
        if (System.getProperty(worker.jarProperty()) != null) return;
        Path dir = RepoRoot.find(GenerateTestSourcesE2eTest.class).resolve(module);
        Path jar =
                BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml"))).mainJar();
        assertThat(jar).as(worker.artifactId() + " built by this workspace").isRegularFile();
        System.setProperty(worker.jarProperty(), jar.toAbsolutePath().toString());
    }

    /** {@link GenerateStubTool} as a jar with a {@code Main-Class}, published with a POM and metadata. */
    private static void toolArtifact(Path repo, String group, String artifact, String version) throws IOException {
        Path vDir = versionDir(repo, group, artifact, version);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, GenerateStubTool.class.getName());
        String entry = GenerateStubTool.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(vDir.resolve(artifact + "-" + version + ".jar"));
                JarOutputStream out = new JarOutputStream(file, manifest);
                InputStream bytes =
                        requireNonNull(GenerateStubTool.class.getClassLoader().getResourceAsStream(entry))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(bytes.readAllBytes());
            out.closeEntry();
        }
    }

    /** A jar holding {@code names.txt}, what the tool reads out of {@code ${unpacked}}. */
    private static void protosArtifact(Path repo, String group, String artifact, String version) throws IOException {
        Path vDir = versionDir(repo, group, artifact, version);
        try (OutputStream file = Files.newOutputStream(vDir.resolve(artifact + "-" + version + ".jar"));
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("names.txt"));
            out.write("from-the-jar\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /** The Maven-layout version directory, with the artifact's metadata and POM already written. */
    private static Path versionDir(Path repo, String group, String artifact, String version) throws IOException {
        Path aDir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Path vDir = Files.createDirectories(aDir.resolve(version));
        Files.writeString(aDir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%1$s</groupId>
                  <artifactId>%2$s</artifactId>
                  <versioning>
                    <latest>%3$s</latest>
                    <release>%3$s</release>
                    <versions><version>%3$s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, version));
        Files.writeString(vDir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version));
        return vDir;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, text);
    }
}
