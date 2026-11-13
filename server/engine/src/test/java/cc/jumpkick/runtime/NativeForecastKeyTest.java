// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native-image forecast keys the image with the step's own code: {@link PlannerNative#keyInputs}
 * resolves every input for both, and {@link PlannerNative#imageKey} spells it. The forecast differs
 * only in reading a wiped jar as the one the build restores, and in never fetching: an input that is
 * not on this machine makes it provisional rather than a different key.
 */
class NativeForecastKeyTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    /** A step's environment that may fetch, printing nothing: what the build resolves with. */
    private static final PlannerNative.KeyEnv BUILD = new PlannerNative.KeyEnv() {
        @Override
        public void label(String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean mayFetch() {
            return true;
        }
    };

    @Test
    void the_forecast_resolves_the_same_inputs_and_key_as_the_step(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp, "[\"-O2\"]", "");
        PlannerNative.KeyInputs step = f.inputs(BUILD);
        PlannerNative.KeyInputs forecast = f.inputs(PlannerNative.KeyEnv.LOCAL);

        assertThat(forecast).isEqualTo(step);
        assertThat(PlannerNative.imageKey(forecast, ClasspathFingerprint.ON_DISK)
                        .key())
                .isEqualTo(PlannerNative.imageKey(step, ClasspathFingerprint.ON_DISK)
                        .key());
    }

    /**
     * After {@code jk clean} the jar is gone; the forecast reads it as the bytes the build restores
     * (the package forecast pins its content), so the key matches the one the step stored. A jar
     * nobody pinned is a missing entry: never a false restore.
     */
    @Test
    void a_wiped_jar_keys_as_the_one_the_build_restores(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp, "[\"-O2\"]", "");
        PlannerNative.KeyInputs in = f.inputs(BUILD);
        String stored = PlannerNative.imageKey(in, ClasspathFingerprint.ON_DISK).key();
        Path cache = tmp.resolve("cache");
        ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        String sha = ac.cas()
                .hashFromPath(ac.cas().put("jar-bytes".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        Files.delete(f.layout.mainJar());

        Map<Path, String> pinned = new HashMap<>();
        assertThat(PlannerNative.imageKey(in, jar -> PackagingKeys.fingerprintJarOrCached(jar, ac, pinned))
                        .key())
                .as("unpinned: a missing classpath entry, not the stored key")
                .isNotEqualTo(stored);

        pinned.put(f.layout.mainJar().toAbsolutePath().normalize(), sha);
        assertThat(PlannerNative.imageKey(in, jar -> PackagingKeys.fingerprintJarOrCached(jar, ac, pinned))
                        .key())
                .isEqualTo(stored);
    }

    /** What the step keys on moves the forecast's key with it: args, and a trained reachability tree. */
    @Test
    void a_changed_arg_or_trained_tree_moves_both_keys(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp, "[\"-O2\"]", "");
        String before = PlannerNative.imageKey(f.inputs(PlannerNative.KeyEnv.LOCAL), ClasspathFingerprint.ON_DISK)
                .key();

        Fixture changed = fixture(tmp, "[\"-O3\"]", "");
        assertThat(PlannerNative.imageKey(changed.inputs(PlannerNative.KeyEnv.LOCAL), ClasspathFingerprint.ON_DISK)
                        .key())
                .isNotEqualTo(before);

        Path train = f.layout
                .moduleTargetDir()
                .resolve(TrainLayout.ROOT)
                .resolve("merged")
                .resolve(TrainLayout.REACHABILITY);
        Files.createDirectories(train);
        Files.writeString(train.resolve("reachability-metadata.json"), "{}");
        assertThat(PlannerNative.imageKey(f.inputs(PlannerNative.KeyEnv.LOCAL), ClasspathFingerprint.ON_DISK)
                        .key())
                .isNotEqualTo(before);
    }

    /**
     * A lock that pins a metadata repository not yet extracted: the build would fetch it and key the
     * image with it, so the forecast cannot know that key. It is provisional, never "the key
     * without metadata" (the bug the old replay had: it never matched when metadata was pinned).
     */
    @Test
    void an_unextracted_metadata_pin_is_provisional_for_the_forecast(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp, "[]", """

                [native]
                metadata-repository = "0.3.99-test"
                """);
        Session session = Session.defaults().withCacheDir(tmp.resolve("cache"));
        SessionContext.where(session, () -> {
            assertThatThrownBy(() -> f.inputs(PlannerNative.KeyEnv.LOCAL))
                    .isInstanceOf(PlannerNative.NotLocal.class)
                    .hasMessageContaining("0.3.99-test");
            return null;
        });
    }

    private record Fixture(Path dir, JkBuild project, BuildLayout layout, Path graal) {
        PlannerNative.KeyInputs inputs(PlannerNative.KeyEnv env) throws Exception {
            return PlannerNative.keyInputs(
                    env,
                    project,
                    dir,
                    layout,
                    dir.resolve("cache"),
                    dir.resolve("jk-lock.toml"),
                    null,
                    graal,
                    null,
                    List.of(),
                    true);
        }
    }

    private static Fixture fixture(Path tmp, String args, String lockExtra) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("demo"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "t"
                name    = "demo"
                version = "0.0.1"
                java    = 25

                [native]
                enabled = "always"
                main    = "t.Main"
                args    = %s
                """.formatted(args));
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """ + lockExtra);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, project);
        Path jar = layout.mainJar();
        Files.createDirectories(Objects.requireNonNull(jar.getParent()));
        if (!Files.exists(jar)) Files.writeString(jar, "jar-bytes");
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        Files.writeString(graal.resolve("release"), "JAVA_VERSION=\"25\"\n");
        Path bin = Files.createDirectories(graal.resolve("bin"));
        Path nativeImage = bin.resolve("native-image");
        if (!Files.exists(nativeImage)) {
            Files.writeString(nativeImage, "#!/bin/sh\n");
            if (nativeImage.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(nativeImage, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
        return new Fixture(dir, project, layout, graal);
    }
}
