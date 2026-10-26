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
 * {@code @Nullable} field. The same sources compile once the table is gone. {@code exclude} and the
 * JSpecify markings in {@code package-info.java} move the checked set; {@code explicit} makes every
 * class choose.
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

    @Test
    void exclude_and_package_markings_move_the_checked_set_and_explicit_makes_every_class_choose(@TempDir Path tmp)
            throws Exception {
        Path project = Files.createDirectories(tmp.resolve("nullness"));
        Path src = project.resolve("src/main/java");
        dereferencing(src, "com.example.legacy", "Old", "oldLabel");
        dereferencing(src, "com.example.loose", "Loose", "looseLabel");
        Files.writeString(src.resolve("com/example/loose/package-info.java"), """
                @NullUnmarked
                package com.example.loose;

                import org.jspecify.annotations.NullUnmarked;
                """);
        String excluded = IDENTITY + "\n[nullness]\nexclude = [\"com.example.legacy\"]\n";
        Files.writeString(project.resolve("jk.toml"), excluded);
        List<String> diagnostics = new ArrayList<>();
        WorkspaceResult unchecked = build(project, diagnostics);
        assertThat(unchecked.success())
                .as(
                        "an excluded package and an @NullUnmarked one go unchecked; diagnostics:%n%s",
                        String.join("\n", diagnostics))
                .isTrue();

        Path other = dereferencing(src, "org.other", "Strict", "strictLabel");
        Files.writeString(other.resolve("package-info.java"), """
                @NullMarked
                package org.other;

                import org.jspecify.annotations.NullMarked;
                """);
        diagnostics.clear();
        WorkspaceResult marked = build(project, diagnostics);
        assertThat(marked.success())
                .as("@NullMarked checks a package outside the group")
                .isFalse();
        assertThat(String.join("\n", diagnostics))
                .contains("dereferenced expression 'strictLabel' is @Nullable")
                .doesNotContain("oldLabel")
                .doesNotContain("looseLabel");

        Files.delete(other.resolve("Strict.java"));
        Files.writeString(project.resolve("jk.toml"), excluded + "explicit = true\n");
        diagnostics.clear();
        WorkspaceResult explicit = build(project, diagnostics);
        assertThat(explicit.success())
                .as("explicit fails the class no marking covers")
                .isFalse();
        assertThat(String.join("\n", diagnostics))
                .contains("[RequireExplicitNullMarking]")
                .doesNotContain("dereferenced expression");
    }

    /** A class in {@code pkg} that dereferences its {@code @Nullable} field {@code field}; returns its directory. */
    private static Path dereferencing(Path src, String pkg, String name, String field) throws IOException {
        Path dir = Files.createDirectories(src.resolve(pkg.replace('.', '/')));
        Files.writeString(dir.resolve(name + ".java"), """
                package %s;

                import org.jspecify.annotations.Nullable;

                public class %s {
                    @Nullable String %s;

                    int size() {
                        return %s.length();
                    }
                }
                """.formatted(pkg, name, field, field));
        return dir;
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
