// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code [generate.<name>] secret-env} variable reaches the forked tool from {@code env:NAME},
 * keys nothing, and no file under {@code target/} holds its value.
 */
@Tag("integration")
class GenerateSecretEnvE2eTest {

    @Test
    void the_secret_reaches_the_tool_keys_nothing_and_no_file_holds_it(@TempDir Path tmp) throws Exception {
        workerJarFromWorkspace(PluginJar.GENERATOR, "plugins/generator");
        Path repo = tmp.resolve("repo");
        toolArtifact(repo, "com.example", "secret-gen", "1.0");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                java = 25

                [repositories.local]
                url = "%s"

                [generate.api]
                tool       = "com.example:secret-gen:1.0"
                inputs     = ["src/main/gen/api.txt"]
                args       = ["${out}", "DB_PASSWORD"]
                secret-env = { DB_PASSWORD = "env:SHOP_DB_PASSWORD" }
                """.formatted(repo.toUri()));
        write(project.resolve("src/main/gen/api.txt"), "api\n");
        write(project.resolve("src/main/java/com/example/Main.java"), """
                package com.example;

                public final class Main {
                    private Main() {}
                }
                """);
        Files.writeString(project.resolve(".env"), "SHOP_DB_PASSWORD=pw-first-5e2a\n");
        Path cache = tmp.resolve("cache");
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(step(first, "generate-api").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(filesHolding(project.resolve("target"), "pw-first-5e2a")).isEmpty();

        Files.writeString(project.resolve(".env"), "SHOP_DB_PASSWORD=pw-second-8b17\n");
        assertThat(step(build(project, cache), "generate-api").status())
                .as("a new password is no new input")
                .isEqualTo(TaskStatus.SKIPPED);
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(TestInputs.of(project, cache)).run();
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

    /** The files under {@code dir} whose bytes hold {@code text}. */
    private static List<Path> filesHolding(Path dir, String text) throws Exception {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        List<Path> out = new ArrayList<>();
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i + needle.length <= bytes.length; i++) {
                if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                    out.add(file);
                    return;
                }
            }
        });
        return out;
    }

    private static void workerJarFromWorkspace(PluginJar worker, String module) throws IOException {
        if (System.getProperty(worker.jarProperty()) != null) return;
        Path dir = RepoRoot.find(GenerateSecretEnvE2eTest.class).resolve(module);
        Path jar =
                BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml"))).mainJar();
        assertThat(jar).as(worker.artifactId() + " built by this workspace").isRegularFile();
        System.setProperty(worker.jarProperty(), jar.toAbsolutePath().toString());
    }

    /** {@link GenerateSecretStubTool} as a jar with a {@code Main-Class}, published with a POM and metadata. */
    private static void toolArtifact(Path repo, String group, String artifact, String version) throws IOException {
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
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, GenerateSecretStubTool.class.getName());
        String entry = GenerateSecretStubTool.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(vDir.resolve(artifact + "-" + version + ".jar"));
                JarOutputStream out = new JarOutputStream(file, manifest);
                InputStream bytes = requireNonNull(
                        GenerateSecretStubTool.class.getClassLoader().getResourceAsStream(entry))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(bytes.readAllBytes());
            out.closeEntry();
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, text);
    }
}
