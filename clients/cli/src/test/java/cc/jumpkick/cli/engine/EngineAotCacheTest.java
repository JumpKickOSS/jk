// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn.AotMode;
import cc.jumpkick.cli.engine.EngineSpawn.EngineArtifact;
import cc.jumpkick.cli.engine.EngineSpawn.EngineJdk;
import cc.jumpkick.cli.engine.EngineSpawn.EngineTarget;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pure AOT-cache decision logic: key derivation, mode selection, and log-scan detection.
 *
 * <p>{@link IsolatedState} because {@code aotCachePath} keys off the @TempDir {@code paths} but
 * resolves the cache dir from the ambient {@code JkDirs.state()} — by design, one AOT home per
 * machine — and then sweeps every {@code engine-<version>-<16hex>} key that is not the one it just
 * derived. Against the tier's shared {@code test-jk-home} that meant each run leaked a manifest row
 * per synthetic jar (268 by the time was filed), deleted the tier's real 29&nbsp;MB engine
 * AOT cache, and raced sibling forks that had planted a fixture of the same shape.
 */
@IsolatedState
class EngineAotCacheTest {

    private static EngineJdk temurin(String version) {
        return new EngineJdk(Path.of("/opt/jdk"), JdkVendor.TEMURIN, version);
    }

    private static Path jar(Path dir, String name, String bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, bytes);
        return p;
    }

    @Test
    void key_is_stable_for_identical_inputs(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        Files.createDirectories(paths.dir());
        Path jar = jar(dir, "jk-engine-1.jar", "aaa");
        assertThat(EngineSpawn.aotCachePath(paths, jar, temurin("25.0.3")))
                .isEqualTo(EngineSpawn.aotCachePath(paths, jar, temurin("25.0.3")));
    }

    @Test
    void key_changes_on_jar_jdk_version_and_vendor(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        Files.createDirectories(paths.dir());
        Path jarA = jar(dir, "jk-engine-A.jar", "aaa");
        Path jarB = jar(dir, "jk-engine-B.jar", "bbbbb"); // different name + size

        Path base = EngineSpawn.aotCachePath(paths, jarA, temurin("25.0.3"));
        Path diffJar = EngineSpawn.aotCachePath(paths, jarB, temurin("25.0.3"));
        Path diffVersion = EngineSpawn.aotCachePath(paths, jarA, temurin("25.0.4"));
        Path diffVendor = EngineSpawn.aotCachePath(
                paths, jarA, new EngineJdk(Path.of("/opt/jdk"), JdkVendor.ORACLE_GRAALVM, "25.0.3"));
        Path noJdk = EngineSpawn.aotCachePath(paths, jarA, null);

        assertThat(base).isNotEqualTo(diffJar);
        assertThat(base).isNotEqualTo(diffVersion);
        assertThat(base).isNotEqualTo(diffVendor);
        assertThat(base).isNotEqualTo(noJdk);
        assertThat(base.getFileName().toString()).startsWith("engine-").endsWith(".aot");
    }

    @Test
    void stale_caches_and_markers_for_other_keys_are_swept(@TempDir Path dir) throws IOException {
        EnginePaths.Paths paths = EnginePaths.resolve(dir);
        // Derived AOT state is version-scoped (engine-versioning-plan R3): the sweep covers THIS
        // version's dir only — other versions' caches are the GC's business, not ours.
        Path versionDir = paths.dir().resolve(Jk.VERSION);
        Files.createDirectories(versionDir);
        Path staleAot = versionDir.resolve("engine-deadbeefdeadbeef.aot");
        Path staleMarker = versionDir.resolve("engine-deadbeefdeadbeef.noaot");
        Files.writeString(staleAot, "old");
        Files.writeString(staleMarker, "");
        Path jar = jar(dir, "jk-engine-1.jar", "aaa");

        Path current = EngineSpawn.aotCachePath(paths, jar, temurin("25.0.3"));
        Path currentMarker = AotCacheFiles.marker(current);
        // A marker for the CURRENT key must survive; pre-create it and confirm.
        Files.writeString(currentMarker, "");
        EngineSpawn.aotCachePath(paths, jar, temurin("25.0.3")); // second call performs the sweep again

        assertThat(staleAot).doesNotExist();
        assertThat(staleMarker).doesNotExist();
        assertThat(currentMarker).exists();
    }

    @Test
    void choose_mode_none_for_non_jar_non_hotspot_and_marker(@TempDir Path dir) throws IOException {
        Path aot = dir.resolve("engine-x.aot");
        Files.writeString(aot, "cache");
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        EngineArtifact exe = new EngineArtifact(EngineArtifact.Kind.EXE, "jk-engine", "JK_ENGINE_EXE");

        assertThat(EngineSpawn.chooseAotMode(new EngineTarget(exe, null, false, null)))
                .isEqualTo(AotMode.NONE); // non-JAR
        assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, false, aot)))
                .isEqualTo(AotMode.NONE); // GraalVM host
        Files.createFile(AotCacheFiles.marker(aot));
        assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, aot)))
                .isEqualTo(AotMode.NONE); // a live refusal for this key
    }

    /**
     * The engine reads a refusal marker on the same schedule the worker trainer does: honoured
     * inside {@link AotCacheFiles#MARKER_TTL_MILLIS}, expired past it. The engine used to treat one
     * as permanent, so a single transient training failure — a loaded machine, a mapping failure —
     * disabled engine AOT for as long as the jar and the JDK stayed put, which for anyone on a
     * release is the life of the install. {@code PluginAotNoAotMarkerTest} pins the worker side.
     */
    @Test
    void a_refusal_marker_expires_so_a_bad_day_does_not_disable_engine_aot(@TempDir Path dir) throws IOException {
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        Path aot = dir.resolve("engine-0.1.0-0123456789abcdef.aot");
        Files.writeString(aot, "cache"); // a usable cache, so only the marker can force NONE
        Path marker = AotCacheFiles.marker(aot);

        Files.createFile(marker);
        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - AotCacheFiles.MARKER_TTL_MILLIS + 60_000));
        assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, aot)))
                .isEqualTo(AotMode.NONE); // inside the window: still backing off
        assertThat(marker).exists();

        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - AotCacheFiles.MARKER_TTL_MILLIS - 60_000));
        assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, aot)))
                .isEqualTo(AotMode.USE); // expired: the key is given its cache back
        assertThat(marker).doesNotExist(); // deleted, not merely ignored
    }

    @Test
    void choose_mode_train_when_no_cache_use_when_present(@TempDir Path dir) throws IOException {
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        Path missing = dir.resolve("engine-missing.aot");
        Path present = dir.resolve("engine-present.aot");
        Files.writeString(present, "cache");

        String prev = System.getProperty("jk.aot.train");
        try {
            // Property wins over ambient JK_AOT_TRAIN=off (jk test workers / CI). Do not clear —
            // env would keep training disabled.
            System.setProperty("jk.aot.train", "on");
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, missing)))
                    .isEqualTo(AotMode.TRAIN);
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, present)))
                    .isEqualTo(AotMode.USE);

            System.setProperty("jk.aot.train", "off");
            // No train-on-miss, but still map an existing cache.
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, missing)))
                    .isEqualTo(AotMode.NONE);
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, present)))
                    .isEqualTo(AotMode.USE);
        } finally {
            if (prev == null) System.clearProperty("jk.aot.train");
            else System.setProperty("jk.aot.train", prev);
        }
    }

    @Test
    void choose_mode_treats_a_zero_byte_cache_as_missing_and_deletes_it(@TempDir Path dir) throws IOException {
        EngineArtifact jar = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        Path torn = dir.resolve("engine-0.1.0-0123456789abcdef.aot");
        Files.createFile(torn); // a killed trainer's zero-byte leftover

        String prev = System.getProperty("jk.aot.train");
        try {
            System.setProperty("jk.aot.train", "on");
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, torn)))
                    .isEqualTo(AotMode.TRAIN); // never USE — an empty cache maps nothing, forever
            assertThat(torn).doesNotExist(); // removed so the retrain can land cleanly

            Files.createFile(torn);
            System.setProperty("jk.aot.train", "off");
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jar, dir, true, torn)))
                    .isEqualTo(AotMode.NONE); // no train-on-miss, but the torn file still goes
            assertThat(torn).doesNotExist();
        } finally {
            if (prev == null) System.clearProperty("jk.aot.train");
            else System.setProperty("jk.aot.train", prev);
        }
    }

    @Test
    void manifest_status_agrees_with_the_mode_decision_for_the_same_file(@TempDir Path dir) throws IOException {
        EngineArtifact jarArtifact = new EngineArtifact(EngineArtifact.Kind.JAR, "j", "lib");
        Path engineJar = jar(dir, "jk-engine-1.jar", "jarbytes");
        Path cache = dir.resolve("engine-0.1.0-0123456789abcdef.aot");

        String prev = System.getProperty("jk.aot.train");
        try {
            System.setProperty("jk.aot.train", "on");
            // Zero-byte file: the engine must NOT map it — and the manifest must not call it ready.
            Files.createFile(cache);
            EngineSpawn.recordEngineAotManifest(cache, engineJar, temurin("25.0.3"), "0.1.0", "0123456789abcdef");
            assertThat(statusOf(dir, cache)).isEqualTo("pending");
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jarArtifact, dir, true, cache)))
                    .isEqualTo(AotMode.TRAIN);

            // Complete file: manifest says ready, engine maps it.
            Files.writeString(cache, "assembled-cache");
            EngineSpawn.recordEngineAotManifest(cache, engineJar, temurin("25.0.3"), "0.1.0", "0123456789abcdef");
            assertThat(statusOf(dir, cache)).isEqualTo("ready");
            assertThat(EngineSpawn.chooseAotMode(new EngineTarget(jarArtifact, dir, true, cache)))
                    .isEqualTo(AotMode.USE);
        } finally {
            if (prev == null) System.clearProperty("jk.aot.train");
            else System.setProperty("jk.aot.train", prev);
        }
    }

    private static String statusOf(Path aotDir, Path cache) {
        return AotManifest.load(aotDir).stream()
                .filter(e -> e.file().equals(cache.getFileName().toString()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    @Test
    void log_scan_detects_aot_markers(@TempDir Path dir) throws IOException {
        assertThat(EngineSpawn.scanLogForAotError(write(dir, "a.log", "[0.0s][error][aot] boom\n")))
                .isTrue();
        // -Xlog pads the level field to the widest enabled level, so a real error arrives as
        // "[error  ]" whenever warnings are on too. Testing for a literal "[error][aot]" missed
        // the common shape: the trainer saw the refusal and the engine did not.
        assertThat(EngineSpawn.scanLogForAotError(
                        write(dir, "e.log", "[0.004s][error  ][aot] Unable to map shared spaces\n")))
                .isTrue();
        assertThat(EngineSpawn.scanLogForAotError(
                        write(dir, "b.log", "Mismatched values for property jdk.module.addmods: ...\n")))
                .isTrue();
        assertThat(EngineSpawn.scanLogForAotError(write(dir, "c.log", "Disabling optimized module handling\n")))
                .isTrue();
        assertThat(EngineSpawn.scanLogForAotError(write(dir, "d.log", "jk engine: spawning ...\nready\n")))
                .isFalse();
        assertThat(EngineSpawn.scanLogForAotError(dir.resolve("missing.log"))).isFalse();
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }
}
