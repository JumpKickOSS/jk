// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        assertThatThrownBy(() -> PlannerSetup.publishClasspaths(f.ctx, f.in, new Cas(store)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:lib")
                .hasMessageContaining("not on disk after sync");
    }

    @Test
    void publishClasspaths_puts_compile_cp_when_jar_present(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Fixture f = fixture(tmp, store, /* materializeLib */ true);

        PlannerSetup.publishClasspaths(f.ctx, f.in, new Cas(store));

        @SuppressWarnings("unchecked")
        List<Path> cp = (List<Path>) f.ctx.require(BuildPlanner.CLASSPATH);
        assertThat(cp).contains(f.libJar.toAbsolutePath().normalize());
        assertThat(new ClasspathResolver(store).classpathFor(f.lock, ClasspathResolver.COMPILE_MAIN, true))
                .contains(f.libJar.toAbsolutePath().normalize());
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
            RepoArtifactStore.forRepoName(store, "central").materialize("com/foo/lib/1.0/lib-1.0.jar", jarSrc, hex);
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
        public void label(String description) {}

        @Override
        public void output(String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }
    }
}
