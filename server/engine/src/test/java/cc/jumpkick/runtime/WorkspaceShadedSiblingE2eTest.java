// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a shaded library sibling is a workspace module. {@code lib} relocates its own package
 * under {@code [library] relocate}; {@code app} imports the shaded name, which exists only in
 * {@code lib}'s {@code -all.jar}. The schedule admits {@code app}'s compile once that jar is
 * packaged, and the compile reads it.
 */
// Out of the unit tier: the lock resolves the injected test roots from Central.
@Tag("integration")
class WorkspaceShadedSiblingE2eTest {

    @Test
    void a_consumer_compiles_against_the_siblings_relocating_jar(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("shaded-sibling-cache");
        Path ws = workspace(tmp);

        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("app/jk-lock.toml"));

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {});

        assertThat(result.errors()).isEmpty();
        assertThat(result.success())
                .as("app imports com.example.shaded.lib.Util, which only lib's -all.jar carries")
                .isTrue();
        Path shaded = ws.resolve("target/lib/lib/lib-1.0.0-all.jar");
        assertThat(shaded).isRegularFile();
        try (JarFile jar = new JarFile(shaded.toFile())) {
            assertThat(jar.getEntry("com/example/shaded/lib/Util.class")).isNotNull();
            assertThat(jar.getEntry("com/example/lib/Util.class")).isNull();
        }
        assertThat(ws.resolve("target/app/classes/main/com/example/App.class")).isRegularFile();
    }

    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [library]
                relocate = { "com.example.lib" = "com.example.shaded.lib" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example/lib"));
        Files.writeString(lib.resolve("src/com/example/lib/Util.java"), """
                package com.example.lib;
                public final class Util {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                import com.example.shaded.lib.Util;
                public final class App {
                    public static int useLib(int n) { return Util.twice(n); }
                }
                """);
        return ws;
    }
}
