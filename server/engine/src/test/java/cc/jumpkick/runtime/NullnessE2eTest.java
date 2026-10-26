// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [nullness]} alone is the whole setup: the table brings JSpecify, Error Prone and NullAway
 * through the lock, checks the module's group, and fails the build on a planted dereference of a
 * {@code @Nullable} field. The same sources compile once the table is gone.
 *
 * <p>Network tier: the three jars and their closures come from Central at lock time.
 */
@Tag("network")
class NullnessE2eTest {

    private static final String IDENTITY = """
            group   = "com.example"
            name    = "nullness"
            version = "0.1.0"
            java    = 25

            [dependencies]
            jspecify = "org.jspecify:jspecify:1.0.1"
            """;

    @Test
    void the_table_fails_the_planted_dereference_and_the_bare_manifest_compiles_it(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("nullness"));
        Path pkg = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Widget.java"), """
                package com.example;

                import org.jspecify.annotations.Nullable;

                public class Widget {
                    @Nullable String label;

                    int size() {
                        return label.length();
                    }
                }
                """);

        Files.writeString(project.resolve("jk.toml"), IDENTITY + "\n[nullness]\n");
        List<String> diagnostics = new ArrayList<>();
        WorkspaceResult checked = build(project, diagnostics);
        assertThat(checked.errors()).as("graph errors").isEmpty();
        assertThat(checked.success()).as("[nullness] fails the dereference").isFalse();
        assertThat(String.join("\n", diagnostics))
                .contains("[NullAway]")
                .contains("dereferenced expression 'label' is @Nullable");

        Files.writeString(project.resolve("jk.toml"), IDENTITY);
        diagnostics.clear();
        WorkspaceResult bare = build(project, diagnostics);
        assertThat(bare.errors()).isEmpty();
        assertThat(bare.success())
                .as("without [nullness] nothing checks; diagnostics:%n%s", String.join("\n", diagnostics))
                .isTrue();
    }

    private static WorkspaceResult build(Path project, List<String> diagnostics) throws IOException {
        WorkspaceRequest req = new WorkspaceRequest(
                project,
                cacheRoot(),
                null,
                1,
                null, /* skipTests */
                true,
                false,
                1,
                null,
                false, /* freshenLock */
                true);
        WorkspaceBuildListener recorder = new WorkspaceBuildListener() {
            @Override
            public BuildPlanListener onModuleStart(ModulePlan module) {
                return new BuildPlanListener() {
                    @Override
                    public void error(String step, String code, String message) {
                        diagnostics.add(step + ": " + message);
                    }
                };
            }
        };
        return BuildService.buildWorkspace(req, recorder);
    }

    /** As {@link JavacPluginE2eTest}'s: warm across runs, keyed by the compiler worker's content. */
    private static Path cacheRoot() throws IOException {
        Path worker = PluginJar.JAVA_COMPILER.locate(JkStores.storeCas());
        String key = Hashing.sha256Hex(worker).substring(0, 12);
        return TestCaches.dir("nullness-cache-" + key);
    }
}
