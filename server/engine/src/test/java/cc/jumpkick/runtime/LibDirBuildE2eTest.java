// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: {@code [application] lib-dir = true} builds {@code target/<name>.jar} and {@code
 * target/libs/}, the jar runs with {@code java -jar}, and an unchanged rebuild is a cache hit.
 */
@Tag("integration")
class LibDirBuildE2eTest {

    @Test
    void the_lib_dir_layout_builds_runs_with_java_jar_and_rebuilds_from_cache(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("lib-dir-cache");
        Path ws = workspace(tmp);
        lock(ws, cache);

        Labels first = new Labels();
        WorkspaceResult built = build(ws, cache, first);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();

        Path target = ws.resolve("app/target");
        assertThat(target.resolve("app-1.0.0.jar"))
                .as("the thin jar is still written")
                .isRegularFile();
        assertThat(target.resolve("libs/lib-1.0.0.jar")).isRegularFile();
        try (JarFile jar = new JarFile(target.resolve("app.jar").toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue(Attributes.Name.CLASS_PATH)).isEqualTo("libs/lib-1.0.0.jar");
            assertThat(main.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("com.example.app.Main");
        }
        assertThat(runJar(target, "app.jar")).isEqualTo("from lib");

        Labels second = new Labels();
        assertThat(build(ws, cache, second).success()).isTrue();
        assertThat(first.of("app", TaskNames.PACKAGE_LIB_DIR)).startsWith("lay out app.jar");
        assertThat(second.of("app", TaskNames.PACKAGE_LIB_DIR))
                .as("an unchanged rebuild does not lay the directory out again")
                .doesNotStartWith("lay out");
        assertThat(runJar(target, "app.jar")).isEqualTo("from lib");
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib/src/com/example"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(lib.resolve("Lib.java"), """
                package com.example;

                public final class Lib {
                    private Lib() {}

                    public static String text() {
                        return "from lib";
                    }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app/src/com/example/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [application]
                main    = "com.example.app.Main"
                lib-dir = true

                [dependencies]
                lib = { workspace = true }
                """);
        Files.writeString(app.resolve("Main.java"), """
                package com.example.app;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(com.example.Lib.text());
                    }
                }
                """);
        return ws;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        for (String member : List.of("lib", "app")) {
            Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(member).resolve("jk-lock.toml"));
        }
    }

    private static WorkspaceResult build(Path ws, Path cache, Labels labels) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), labels);
    }

    private static String runJar(Path dir, String jar) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), "-jar", jar)
                        .directory(dir.toFile())
                        .redirectErrorStream(true),
                Duration.ofSeconds(60));
        String out = run.output().trim();
        assertThat(run.exit()).as(out).isZero();
        return out;
    }

    /** The last label each module's steps carried. */
    private static final class Labels implements WorkspaceBuildListener {
        private final Map<String, String> last = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    last.put(name + "/" + step, label);
                }
            };
        }

        String of(String module, String step) {
            return last.getOrDefault(module + "/" + step, "");
        }
    }
}
