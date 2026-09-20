// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Protects the user-facing {@code [train] aot-cache = true}: after the tracing-agent run, {@code
 * jk train} records and assembles {@code target/train/app.aot} with the module's JVM, leaves no
 * {@code app.aotconf} behind, and that JVM maps the cache without refusal. Skipped on a host
 * without a GraalVM that carries {@code native-image-agent}.
 */
// Network: the launcher pin from Central.
@Tag("integration")
class TrainAotCacheE2eTest {

    private static final String MANIFEST = """
            name    = "trainapp"
            group   = "com.example"
            version = "1.0.0"
            java    = 25

            [application]
            main = "com.example.Main"

            [train]
            aot-cache = true

            [[train.profile]]
            name = "smoke"

            # No tests: the launcher pin keeps the injected junit-jupiter out of the graph.
            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void train_with_aot_cache_writes_a_cache_the_module_jvm_maps(@TempDir Path tmp) throws Exception {
        List<Path> candidates = graalCandidates();
        Path graal = graalHome(candidates);
        if (graal == null) {
            // The skip reason lands in the report's system-out; an aborted assumption shows no text.
            System.out.println("skipping: no GraalVM with native-image-agent among " + candidates + " (user.home="
                    + System.getProperty("user.home") + ")");
        }
        Assumptions.assumeTrue(graal != null, "no GraalVM with native-image-agent on this host");

        Path project = Files.createDirectories(tmp.resolve("trainapp"));
        Path cache = TestCaches.dir("train-aot-cache");
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        // Reflection the tracing agent records; a surface with no entries fails train.
                        Class<?> self = Class.forName("com.example.Main");
                        System.out.println("Hello from " + self.getMethod("main", String[].class).getName());
                    }
                }
                """);

        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);
        Path lockFile = project.resolve("jk-lock.toml");
        Path javaHome = Path.of(System.getProperty("java.home"));
        TrainConfig config = JkBuildParser.trainConfig(project.resolve("jk.toml"));
        assertThat(config.aotCache()).isTrue();

        List<String> log = new ArrayList<>();
        Session nested = Session.defaults().withCacheDir(cache);
        SessionContext.runWhere(nested, () -> {
            try {
                BuildPlan lock = LockPlans.lockBuildPlan(
                        project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                assertThat(lock.run().errors()).isEmpty();
                BuildPlanResult built = BuildPlanner.coreBuilder(new BuildPlanner.Inputs(
                                project,
                                cache,
                                project.resolve("jk.toml"),
                                lockFile,
                                project,
                                1,
                                0,
                                null,
                                null,
                                true,
                                false,
                                false,
                                false,
                                Set.of(),
                                nested))
                        .build()
                        .run();
                assertThat(built.errors()).isEmpty();
                assertThat(layout.mainJar()).isRegularFile();

                TrainRunner.Result result = TrainRunner.run(
                        project, parsed, layout, cache, lockFile, graal, javaHome, config, null, true, log::add);
                // The train log is the evidence a reader of the report wants: which JVM ran, what
                // the agent saw, how large the cache came out.
                log.forEach(System.out::println);
                assertThat(result.aotWritten()).as(String.join("\n", log)).isTrue();
                assertThat(result.surface().entries()).isNotEmpty();

                Path target = layout.moduleTargetDir();
                Path aot = TrainLayout.aotCache(target);
                assertThat(aot).isRegularFile();
                assertThat(Files.size(aot)).isGreaterThan(0);
                assertThat(target.resolve("train/app.aotconf")).doesNotExist();
                assertThat(TrainLayout.fingerprint(target)).isRegularFile();

                // The module's JVM maps the cache: -Xlog:aot is the only place a refusal shows.
                List<String> command = new ArrayList<>();
                command.add(JdkFingerprint.java(javaHome).toString());
                command.add("-Xlog:aot=info");
                command.add("-XX:AOTCache=" + aot.toAbsolutePath());
                command.addAll(
                        TrainRunner.launchArgs(project, parsed, layout, cache, lockFile, layout.mainJar(), log::add));
                String verify = output(project, command);
                assertThat(AotCacheFiles.refusal(verify)).as(verify).isNull();
                assertThat(verify).contains("Hello from main");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Where a GraalVM may live on a developer host: the Graal and Java environment variables, this
     * JVM, and the JDK install roots under the real user profile (the sandbox redirects {@code
     * user.home}, so the profile comes from the environment first).
     */
    private static List<Path> graalCandidates() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("GRAALVM_HOME", "JAVA_HOME")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) candidates.add(Path.of(value));
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        List<Path> homes = new ArrayList<>();
        for (String env : List.of("USERPROFILE", "HOME")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) homes.add(Path.of(value));
        }
        homes.add(Path.of(System.getProperty("user.home")));
        homes.add(JkDirs.jdks());
        for (Path home : homes) {
            for (Path dir : List.of(home.resolve(".jdks"), home.resolve(".jk/jdks"), home)) {
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> installs = Files.list(dir)) {
                    installs.filter(Files::isDirectory)
                            .filter(d -> d.getFileName()
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                                    .contains("graal"))
                            .sorted()
                            .forEach(candidates::add);
                } catch (IOException ignored) {
                    // an unreadable install dir just contributes no candidate
                }
            }
        }
        return candidates;
    }

    /** The first candidate that carries the tracing agent, or null. */
    private static @Nullable Path graalHome(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path agentHome = TrainRunner.resolveAgentJavaHome(candidate, candidate);
            if (agentHome != null) return agentHome;
        }
        return null;
    }

    private static String output(Path dir, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] bytes = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
