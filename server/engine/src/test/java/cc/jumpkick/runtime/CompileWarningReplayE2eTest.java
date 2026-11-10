// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkBuild;
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
 * A build that does not run the compiler says the warnings the classes on disk were compiled with:
 * a compile found up to date and one restored from the cache say them again, marked replayed, and
 * a Kotlin compile that succeeds says its warnings at all.
 */
@Tag("integration")
class CompileWarningReplayE2eTest {

    @Test
    void a_java_build_that_does_not_compile_says_the_compiles_warnings_again(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("warning-replay-cache");
        Path ws = workspace(tmp, "");
        Path src = Files.createDirectories(ws.resolve("app/src/com/example"));
        Files.createDirectories(ws.resolve("app/resources"));
        Files.writeString(ws.resolve("app/resources/app.properties"), "edition=first\n");
        Files.writeString(src.resolve("Old.java"), """
                package com.example;

                public final class Old {
                    private Old() {}

                    @Deprecated
                    public static void f() {}
                }
                """);
        Files.writeString(src.resolve("Uses.java"), """
                package com.example;

                public final class Uses {
                    private Uses() {}

                    public static void use() {
                        Old.f();
                    }
                }
                """);
        lock(ws, cache);

        List<Said> first = build(ws, cache);
        assertThat(first).as("the compile").singleElement().satisfies(w -> {
            assertThat(w.message()).contains("Uses.java").contains("f() in com.example.Old has been deprecated");
            assertThat(w.replayed()).isFalse();
        });

        List<Said> upToDate = build(ws, cache);
        assertThat(upToDate)
                .as("a module the build does not run")
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.message()).isEqualTo(first.getFirst().message());
                    assertThat(w.replayed()).isTrue();
                });

        Files.writeString(ws.resolve("app/resources/app.properties"), "edition=second\n");
        List<Said> resourceEdit = build(ws, cache);
        assertThat(resourceEdit)
                .as("a compile found up to date in a module that runs")
                .singleElement()
                .satisfies(w -> {
                    assertThat(w.message()).isEqualTo(first.getFirst().message());
                    assertThat(w.replayed()).isTrue();
                });

        PathUtil.deleteRecursively(ws.resolve("app/target"));
        List<Said> restored = build(ws, cache);
        assertThat(restored).as("restored from the cache").singleElement().satisfies(w -> {
            assertThat(w.message()).isEqualTo(first.getFirst().message());
            assertThat(w.replayed()).isTrue();
        });
    }

    @Test
    void a_kotlin_compile_that_succeeds_says_its_warnings_and_a_build_that_skips_it_says_them_again(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("warning-replay-cache");
        Path ws = workspace(tmp, "kotlin = \"^2.4.10\"");
        Path src = Files.createDirectories(ws.resolve("app/src/com/example"));
        Files.writeString(src.resolve("Old.kt"), """
                package com.example

                @Deprecated("use g")
                fun f() {}

                fun g() {}
                """);
        Files.writeString(src.resolve("Uses.kt"), """
                package com.example

                fun use() {
                    f()
                }
                """);
        lock(ws, cache);

        List<Said> first = build(ws, cache);
        assertThat(first).as("the compile").anySatisfy(w -> {
            assertThat(w.message()).contains("Uses.kt").contains("deprecated");
            assertThat(w.replayed()).isFalse();
        });

        List<Said> upToDate = build(ws, cache);
        assertThat(upToDate)
                .extracting(Said::message)
                .isEqualTo(first.stream().map(Said::message).toList());
        assertThat(upToDate).allMatch(Said::replayed);
    }

    /** One compiler warning as the build said it. */
    private record Said(String step, String message, boolean replayed) {}

    private static List<Said> build(Path ws, Path cache) {
        List<Said> said = new ArrayList<>();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {
                    @Override
                    public void onModuleWarning(Path dir, String module, String step, String code, String message) {
                        synchronized (said) {
                            said.add(new Said(step, message, true));
                        }
                    }

                    @Override
                    public BuildPlanListener onModuleStart(ModulePlan module) {
                        return new BuildPlanListener() {
                            @Override
                            public void warn(String step, String code, String message) {
                                warn(step, code, message, false);
                            }

                            @Override
                            public void warn(String step, String code, String message, boolean replayed) {
                                if (!code.equals("javac") && !code.equals("kotlinc")) return;
                                synchronized (said) {
                                    said.add(new Said(step, message, replayed));
                                }
                            }
                        };
                    }
                });
        assertThat(result.success()).as("build succeeded: " + result.errors()).isTrue();
        synchronized (said) {
            return List.copyOf(said);
        }
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));
    }

    /** A workspace of one module {@code app}, with {@code extra} among its top-level keys. */
    private static Path workspace(Path tmp, String extra) throws Exception {
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
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false
                %s

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(extra));
        return ws;
    }
}
