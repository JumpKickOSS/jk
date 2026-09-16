// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A first-party plugin row records which jk built last; the running jk rewrites it in place and
 * moves nothing else in the lock.
 */
class FirstPartyPinsTest {

    private static final String SPRING_BOOT = "cc.jumpkick:jk-spring-boot";
    private static final String MICRONAUT = "cc.jumpkick:jk-micronaut";
    private static final String VENDORED = "com.acme:acme-rules";
    private static final String DIGEST = "sha256:" + "ee".repeat(32);

    private static final Lockfile.Artifact LIBRARY =
            new Lockfile.Artifact("org.example:lib", "1.2.3", "central", "sha256:" + "ab".repeat(32), null, List.of());

    @Test
    void rows_another_jk_pinned_follow_this_jk_and_nothing_else_moves(@TempDir Path tmp) throws Exception {
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(
                Lockfile.empty("0.0.1")
                        .withArtifacts(List.of(LIBRARY))
                        .withPlugins(List.of(
                                new Lockfile.PluginEntry(SPRING_BOOT, "0.0.1", DIGEST),
                                new Lockfile.PluginEntry(VENDORED, "2.0.0", DIGEST),
                                Lockfile.PluginEntry.workspace("cc.jumpkick:jk-guards-junit", "0.0.1", "guards"),
                                new Lockfile.PluginEntry(MICRONAUT, "0.0.1", DIGEST))),
                lockFile);
        String manifestsSha = LockfileReader.read(lockFile).manifestsSha256();

        List<FirstPartyPins.Repin> moved = FirstPartyPins.follow(tmp, Set.of(MICRONAUT));

        assertThat(moved).containsExactly(new FirstPartyPins.Repin(SPRING_BOOT, "0.0.1"));
        Lockfile after = LockfileReader.read(lockFile);
        assertThat(after.generatedBy()).isEqualTo("jk " + JkVersion.VERSION);
        assertThat(after.manifestsSha256()).isEqualTo(manifestsSha);
        assertThat(after.artifacts()).containsExactly(LIBRARY);
        Lockfile.PluginEntry springBoot = row(after, SPRING_BOOT);
        assertThat(springBoot.version()).isEqualTo(JkVersion.VERSION);
        if (Versions.isPreRelease(JkVersion.VERSION))
            assertThat(springBoot.isVersionOnly()).isTrue();
        // Declared by a [plugins] table: the declaration pins it, not jk.
        assertThat(row(after, MICRONAUT)).isEqualTo(new Lockfile.PluginEntry(MICRONAUT, "0.0.1", DIGEST));
        assertThat(row(after, VENDORED)).isEqualTo(new Lockfile.PluginEntry(VENDORED, "2.0.0", DIGEST));
        assertThat(row(after, "cc.jumpkick:jk-guards-junit").isWorkspace()).isTrue();

        // Settled: a second pass writes nothing.
        byte[] bytes = Files.readAllBytes(lockFile);
        assertThat(FirstPartyPins.follow(tmp, Set.of(MICRONAUT))).isEmpty();
        assertThat(Files.readAllBytes(lockFile)).isEqualTo(bytes);
    }

    @Test
    void a_row_at_this_jk_is_already_settled(@TempDir Path tmp) throws Exception {
        Path lockFile = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION)
                        .withPlugins(List.of(FirstPartyPins.running(PluginJar.SPRING_BOOT, lockFile))),
                lockFile);
        byte[] bytes = Files.readAllBytes(lockFile);

        assertThat(FirstPartyPins.follow(tmp, Set.of())).isEmpty();
        assertThat(Files.readAllBytes(lockFile)).isEqualTo(bytes);
    }

    /** A lock without a manifests digest is stale; the relock that follows owns its rewrite. */
    @Test
    void a_lock_without_a_manifests_digest_is_left_to_the_relock(@TempDir Path tmp) throws Exception {
        Path lockFile = tmp.resolve("jk-lock.toml");
        Files.writeString(lockFile, """
                version = 1
                generated-by = "jk 0.0.1"
                resolution-algorithm = "pubgrub-v1"

                [[plugin]]
                coordinate = "cc.jumpkick:jk-spring-boot"
                version    = "0.0.1"
                checksum   = "%s"
                """.formatted(DIGEST));
        byte[] bytes = Files.readAllBytes(lockFile);

        assertThat(FirstPartyPins.follow(tmp, Set.of())).isEmpty();
        assertThat(Files.readAllBytes(lockFile)).isEqualTo(bytes);
    }

    @Test
    void no_lock_is_nothing_to_follow(@TempDir Path tmp) throws Exception {
        assertThat(FirstPartyPins.follow(tmp, Set.of())).isEmpty();
    }

    @Test
    void the_line_names_each_moved_row_once() {
        String line = FirstPartyPins.describe(List.of(
                new FirstPartyPins.Repin(SPRING_BOOT, "0.13.2"), new FirstPartyPins.Repin(MICRONAUT, "0.13.1")));
        assertThat(line)
                .startsWith("jk-lock.toml: jk-spring-boot 0.13.2, jk-micronaut 0.13.1 → " + JkVersion.VERSION)
                .contains("first-party plugins follow the running jk");
    }

    private static Lockfile.PluginEntry row(Lockfile lock, String coordinate) {
        return lock.plugins().stream()
                .filter(e -> e.coordinate().equals(coordinate))
                .findFirst()
                .orElseThrow();
    }
}
