// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member with Kotlin sources and no {@code kotlin} key, its own or the workspace root's, fails
 * its build naming the root and the key and version to add, wherever a Kotlin compile would have
 * collected them: it built "OK" with no compile step and an empty jar, and the one warning that
 * said why was easy to miss (a real project's Kotlin extensions module). {@code .kt} files no
 * compile collects, such as a Java module's test data, build with the warning they always had.
 */
@Tag("integration")
class UncompiledKotlinE2eTest {

    @Test
    void kotlin_sources_under_src_main_kotlin_with_no_kotlin_key_fail_the_build(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path src = Files.createDirectories(ws.resolve("app/src/main/kotlin/com/example"));
        Files.writeString(src.resolve("Greeter.kt"), "package com.example\n\nfun greet() = \"hi\"\n");

        Built built = build(ws);

        assertThat(built.result().success())
                .as("a build that compiles none of its sources")
                .isFalse();
        assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                .contains("src/main/kotlin has Kotlin sources that are not compiled; add kotlin = \""
                        + KotlinResolver.DEFAULT_VERSION + "\" to jk.toml"));
    }

    @Test
    void kotlin_sources_under_src_main_java_with_no_kotlin_key_fail_the_build(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path src = Files.createDirectories(ws.resolve("app/src/main/java/com/example"));
        Files.writeString(src.resolve("GreeterKt.kt"), "package com.example\n\nfun greet() = \"hi\"\n");

        Built built = build(ws);

        assertThat(built.result().success()).isFalse();
        assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                .contains("src/main/java has .kt sources that are not compiled; add kotlin = \""
                        + KotlinResolver.DEFAULT_VERSION + "\" to jk.toml"));
    }

    @Test
    void kotlin_sources_in_a_test_suites_root_with_no_kotlin_key_fail_the_build(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        write(ws.resolve("app/src/main/java/com/example/App.java"), "package com.example;\n\npublic class App {}\n");
        write(ws.resolve("app/src/test/kotlin/com/example/AppTest.kt"), "package com.example\n\nclass AppTest\n");

        Built built = build(ws);

        assertThat(built.result().success()).isFalse();
        assertThat(built.errors()).anySatisfy(e -> assertThat(e)
                .contains("src/test/kotlin has Kotlin sources that are not compiled; add kotlin = \""));
    }

    @Test
    void kotlin_test_data_outside_every_source_root_builds_with_the_warning(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        write(ws.resolve("app/src/main/java/com/example/App.java"), "package com.example;\n\npublic class App {}\n");
        write(ws.resolve("app/src/test/data/Sample.kt"), "fun sample() = 1\n");
        write(ws.resolve("app/src/test/fixtures/parser/input/Script.kt"), "fun script() = 2\n");

        Built built = build(ws);

        assertThat(built.result().success()).as("test data is never compiled").isTrue();
        assertThat(built.warnings()).anySatisfy(w -> assertThat(w)
                .contains("src has .kt sources that are not compiled; add kotlin = \"<version>\" to jk.toml"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private record Built(WorkspaceResult result, List<String> errors, List<String> warnings) {}

    private static Built build(Path ws) throws Exception {
        Path cache = TestCaches.dir("uncompiled-kotlin-cache");
        // Locked as `jk build` would find it, so the run reaches parse-build: a workspace with no
        // lock stops in preflight, before any module's manifest rows are read.
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {
                    @Override
                    public BuildPlanListener onModuleStart(ModulePlan module) {
                        return new BuildPlanListener() {
                            @Override
                            public void warn(String step, String code, String message) {
                                synchronized (errors) {
                                    warnings.add(message);
                                }
                            }

                            @Override
                            public void error(String step, String code, String message) {
                                synchronized (errors) {
                                    errors.add(message);
                                }
                            }
                        };
                    }
                });
        synchronized (errors) {
            errors.addAll(result.errors());
            return new Built(result, List.copyOf(errors), List.copyOf(warnings));
        }
    }

    /** A workspace whose root declares {@code java = 25}, and one member {@code app} that inherits it. */
    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                name    = "app"
                javadoc = false
                """);
        return ws;
    }
}
