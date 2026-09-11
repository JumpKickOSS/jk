// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.runtime.workspace.BuildService;
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
 * A {@code [javac]} plugin declared in the manifest runs inside the real compile: Error Prone with
 * NullAway at error severity, both resolved from Central through the lock as
 * {@code [processor-dependencies]}, fails the build on a planted dereference of a
 * {@code @Nullable} field, and the same sources compile once the table is gone.
 *
 * <p>Network tier: the two jars and their closures come from Central at lock time.
 */
@Tag("network")
class JavacPluginE2eTest {

    private static final String IDENTITY = """
            group   = "com.example"
            name    = "nullness"
            version = "0.1.0"
            java    = 25

            [processor-dependencies]
            error_prone_core = { group = "com.google.errorprone", name = "error_prone_core", version = "latest" }
            nullaway         = { group = "com.uber.nullaway", name = "nullaway", version = "latest" }
            """;

    private static final String JAVAC = """

            [javac]
            plugins = { ErrorProne = { options = ["-XepDisableAllChecks", "-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=com.example"] } }
            args    = ["-XDcompilePolicy=simple", "--should-stop=ifError=FLOW"]
            """;

    @Test
    void nullaway_fails_the_planted_dereference_and_the_bare_manifest_compiles_it(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("nullness"));
        Path pkg = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Nullable.java"), """
                package com.example;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;

                @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Nullable {}
                """);
        Files.writeString(pkg.resolve("Widget.java"), """
                package com.example;

                public class Widget {
                    @Nullable String label;

                    int size() {
                        return label.length();
                    }
                }
                """);

        Files.writeString(project.resolve("jk.toml"), IDENTITY + JAVAC);
        List<String> diagnostics = new ArrayList<>();
        WorkspaceResult guarded = build(project, diagnostics);
        assertThat(guarded.errors()).as("graph errors").isEmpty();
        assertThat(guarded.success())
                .as("NullAway at ERROR fails the dereference")
                .isFalse();
        assertThat(String.join("\n", diagnostics))
                .contains("[NullAway]")
                .contains("dereferenced expression 'label' is @Nullable");

        Files.writeString(project.resolve("jk.toml"), IDENTITY);
        diagnostics.clear();
        WorkspaceResult bare = build(project, diagnostics);
        assertThat(bare.errors()).isEmpty();
        assertThat(bare.success())
                .as("without [javac] the processor path is inert; diagnostics:%n%s", String.join("\n", diagnostics))
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

    /**
     * Persists across runs to keep the network warm, keyed by the worker jar's content so a
     * rebuilt worker never meets a store that materialized the previous one.
     */
    private static Path cacheRoot() throws IOException {
        Path worker = PluginJar.JAVA_COMPILER.locate(JkStores.storeCas());
        String key = Hashing.sha256Hex(worker).substring(0, 12);
        return Path.of(System.getProperty("user.dir"), "build", "javac-plugin-cache-" + key);
    }
}
