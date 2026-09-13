// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classpaths are published after {@code resolve-deps} sync. Resolving them in parse-build on a
 * cold store soft-skipped missing jars and left compile with an empty CP.
 */
class ClasspathAfterSyncTest {

    @Test
    void publishClasspaths_requires_jars_on_disk(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Files.createDirectories(store);
        Fixture f = fixture(tmp, store, /* materializeLib */ false);

        assertThatThrownBy(
                        () -> PlannerSetup.publishClasspaths(f.ctx, f.in, new Cas(store), new PluginBuild.StepTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:lib")
                .hasMessageContaining("not on disk after sync");
    }

    @Test
    void publishClasspaths_puts_compile_cp_when_jar_present(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Fixture f = fixture(tmp, store, /* materializeLib */ true);

        PlannerSetup.publishClasspaths(f.ctx, f.in, new Cas(store), new PluginBuild.StepTools());

        List<Path> cp = f.ctx.require(BuildPlanner.CLASSPATH);
        assertThat(cp).contains(f.libJar.toAbsolutePath().normalize());
        assertThat(new ClasspathResolver(store).classpathFor(f.lock, ClasspathResolver.COMPILE_MAIN, true))
                .contains(f.libJar.toAbsolutePath().normalize());
    }

    @Test
    void tests_kind_sibling_without_test_output_fails_in_setup_not_compile_test(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), "package com.example; public final class Lib {}");
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"

                [test-dependencies]
                lib = { workspace = true, kind = "tests" }
                """);

        JkBuild project = JkBuildParser.parse(app.resolve("jk.toml"));
        JkBuild libManifest = JkBuildParser.parse(lib.resolve("jk.toml"));
        // lib's main jar exists but its test classes do not — the --skip-tests shape. The resolve
        // writes the precise cause; the setup guard must surface it instead of letting
        // compile-test fail with `cannot find symbol` at the wrong file.
        Path libJar = BuildLayout.of(lib, libManifest).mainJar();
        Files.createDirectories(libJar.getParent());
        Files.writeString(libJar, "jar-bytes");

        StashContext ctx = new StashContext();
        ctx.put(BuildPlanner.LOCKFILE, emptyLock());
        ctx.put(BuildPlanner.PROJECT, project);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                app,
                store,
                app.resolve("jk.toml"),
                app.resolve("jk-lock.toml"),
                app,
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

        assertThatThrownBy(() -> PlannerSetup.publishClasspaths(ctx, in, new Cas(store), new PluginBuild.StepTools()))
                .hasMessageContaining("missing workspace siblings");
        assertThat(ctx.errors)
                .as("the written diagnostic reaches the failure report")
                .anyMatch(e -> e.contains("test sibling not built")
                        && e.contains("tests kind")
                        && e.contains("expected test classes at"));
    }

    @Test
    void skip_tests_tolerates_unbuilt_test_scope_siblings(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"

                [test]
                fixtures = true
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"

                [dependencies]
                lib.workspace = true

                [test-dependencies]
                lib = { workspace = true, fixtures = true }
                """);

        JkBuild project = JkBuildParser.parse(app.resolve("jk.toml"));
        JkBuild libManifest = JkBuildParser.parse(lib.resolve("jk.toml"));
        // The production build produced lib's main jar; a --skip-tests plan never compiles its
        // fixtures, and nothing in that plan consumes the test classpath.
        Path libJar = BuildLayout.of(lib, libManifest).mainJar();
        Files.createDirectories(libJar.getParent());
        Files.writeString(libJar, "jar-bytes");

        StashContext ctx = new StashContext();
        ctx.put(BuildPlanner.LOCKFILE, emptyLock());
        ctx.put(BuildPlanner.PROJECT, project);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                app,
                store,
                app.resolve("jk.toml"),
                app.resolve("jk-lock.toml"),
                app,
                1,
                0,
                null,
                null,
                /* skipTests */ true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());

        PlannerSetup.publishClasspaths(ctx, in, new Cas(store), new PluginBuild.StepTools());
        assertThat(ctx.errors).isEmpty();
        assertThat(ctx.values).containsKey(BuildPlanner.CLASSPATH);
        assertThat(ctx.values).containsKey(BuildPlanner.COMPILE_TEST_CP);

        // The same tree with tests planned is still a setup failure that names the sibling.
        BuildPlanner.Inputs withTests = new BuildPlanner.Inputs(
                app,
                store,
                app.resolve("jk.toml"),
                app.resolve("jk-lock.toml"),
                app,
                1,
                0,
                null,
                null,
                /* skipTests */ false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        assertThatThrownBy(() ->
                        PlannerSetup.publishClasspaths(ctx, withTests, new Cas(store), new PluginBuild.StepTools()))
                .hasMessageContaining("missing workspace siblings");
        assertThat(ctx.errors).anyMatch(e -> e.contains("test sibling not built") && e.contains("fixtures"));
    }

    private static Lockfile emptyLock() {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of());
    }

    private static Fixture fixture(Path tmp, Path store, boolean materializeLib) throws Exception {
        Path module = tmp.resolve("app");
        Files.createDirectories(module);
        Files.writeString(module.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);

        Path jarSrc = tmp.resolve("lib.bin");
        Files.writeString(jarSrc, "lib-bytes");
        String hex = Hashing.sha256Hex(jarSrc);
        Path libJar = store.resolve("repos/central/com/foo/lib/1.0/lib-1.0.jar");
        if (materializeLib) {
            RepoArtifactStore.forStoreId(store, "central").materialize("com/foo/lib/1.0/lib-1.0.jar", jarSrc, hex);
        }

        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "com.foo:lib:jar:",
                        "1.0",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:" + hex,
                        null,
                        List.of(Scope.MAIN),
                        List.of())));

        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        StashContext ctx = new StashContext();
        ctx.put(BuildPlanner.LOCKFILE, lock);
        ctx.put(BuildPlanner.PROJECT, project);

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                module,
                store,
                module.resolve("jk.toml"),
                module.resolve("jk-lock.toml"),
                module,
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

        return new Fixture(ctx, in, lock, libJar);
    }

    private record Fixture(StashContext ctx, BuildPlanner.Inputs in, Lockfile lock, Path libJar) {}

    private static final class StashContext implements TaskContext {
        private final Map<BuildPlanKey<?>, Object> values = new HashMap<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {
            values.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.ofNullable((T) values.get(key));
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            return get(key).orElseThrow(() -> new IllegalStateException("no " + key));
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {
            errors.add(message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    }
}
