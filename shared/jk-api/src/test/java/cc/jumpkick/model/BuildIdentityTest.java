// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildIdentityTest {

    private static final String SALTED = "#" + BuildIdentity.CACHE_KEY_SALT;

    /**
     * A key names the version and the salt and nothing of the running code, on a -SNAPSHOT too: an
     * engine rebuilt from other sources replays every action its change does not reach.
     */
    @Test
    void keys_fold_no_engine_identity_on_a_snapshot_either() {
        assertThat(BuildIdentity.compose("1.0.0")).isEqualTo("1.0.0" + SALTED);
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT")).isEqualTo("1.0.0-SNAPSHOT" + SALTED);
        try {
            BuildIdentity.overrideBuildIdForTests("feedfacecafe");
            String one = BuildIdentity.cacheKeyVersion();
            BuildIdentity.overrideBuildIdForTests("0123456789ab");
            assertThat(BuildIdentity.cacheKeyVersion()).isEqualTo(one).isEqualTo(JkVersion.VERSION + SALTED);
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
        }
    }

    @Test
    void turning_the_salt_turns_every_key() {
        assertThat(BuildIdentity.compose("1.0.0", BuildIdentity.CACHE_KEY_SALT))
                .isNotEqualTo(BuildIdentity.compose("1.0.0", BuildIdentity.CACHE_KEY_SALT + 1));
        assertThat(BuildIdentity.cacheKeyVersion()).isEqualTo(BuildIdentity.compose(JkVersion.VERSION));
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_get_no_jar_identity() {
        assertThat(BuildIdentity.buildId()).isEmpty();
        assertThat(BuildIdentity.keyId()).isEmpty();
        assertThat(BuildIdentity.cacheKeyVersion()).isEqualTo(JkVersion.VERSION + SALTED);
    }

    @Test
    void the_override_stands_in_for_the_derived_id_until_cleared() {
        try {
            BuildIdentity.overrideBuildIdForTests("feedfacecafe");
            assertThat(BuildIdentity.buildId()).isEqualTo("feedfacecafe");
            assertThat(BuildIdentity.keyId()).isEqualTo("feedfacecafe");
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
        }
        assertThat(BuildIdentity.buildId()).isEmpty();
    }

    /**
     * Two builds of one version are ordered by the build time the packaging wrote into the
     * archive's manifest, which a reinstall cannot move the way it moves a file's mtime; an archive
     * without the attribute, or with one that is not an instant, has no time.
     */
    @Test
    void the_build_time_is_the_manifests_attribute_or_nothing() {
        Manifest stamped = new Manifest();
        stamped.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        stamped.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, "2026-09-17T15:57:16Z");
        assertThat(BuildIdentity.builtAt(stamped)).isEqualTo(Instant.parse("2026-09-17T15:57:16Z"));

        Manifest bare = new Manifest();
        bare.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        assertThat(BuildIdentity.builtAt(bare)).isNull();

        Manifest garbled = new Manifest();
        garbled.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, "yesterday");
        assertThat(BuildIdentity.builtAt(garbled)).isNull();
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_have_no_build_time() {
        assertThat(BuildIdentity.builtAt()).isNull();
    }

    @Test
    void the_commit_is_the_manifests_attribute_and_reads_short() {
        String sha = "4ee07400a592deadbeef4ee07400a592deadbeef";
        Manifest stamped = new Manifest();
        stamped.getMainAttributes().putValue(BuildIdentity.BUILD_COMMIT_ATTRIBUTE, sha);
        assertThat(BuildIdentity.builtFrom(stamped)).isEqualTo(sha);
        assertThat(BuildIdentity.describeCommit(BuildIdentity.builtFrom(stamped)))
                .isEqualTo("4ee07400a592");

        Manifest bare = new Manifest();
        assertThat(BuildIdentity.builtFrom(bare)).isNull();
        assertThat(BuildIdentity.describeCommit(null)).isEqualTo("unknown");
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_have_no_commit() {
        assertThat(BuildIdentity.builtFrom()).isNull();
    }

    /**
     * The engine jar of each commit carries that commit and its time in its manifest, so an engine
     * module repackaged for a test-only edit is another file with the same code. Keyed by the whole
     * file, every action key of the next build moved after such an install and the push gate ran
     * every suite again; the code's identity holds, and a changed class or another
     * manifest attribute still moves it.
     */
    @Test
    void the_code_identity_ignores_the_packaging_stamps_and_nothing_else(@TempDir Path dir) throws IOException {
        Path one = jar(dir.resolve("one.jar"), "4ee07400a592deadbeef4ee07400a592deadbeef", "2026-11-15T16:27:23Z", "A");
        Path two = jar(
                dir.resolve("two.jar"), "a2136bc4924d9b3e8afce29e25e8ce72a48d060e-dirty", "2026-11-15T16:31:51Z", "A");
        assertThat(Hashing.sha256Hex(one)).isNotEqualTo(Hashing.sha256Hex(two));
        assertThat(BuildIdentity.codeIdentity(one)).isEqualTo(BuildIdentity.codeIdentity(two));

        Path code =
                jar(dir.resolve("code.jar"), "4ee07400a592deadbeef4ee07400a592deadbeef", "2026-11-15T16:27:23Z", "B");
        assertThat(BuildIdentity.codeIdentity(code)).isNotEqualTo(BuildIdentity.codeIdentity(one));

        Path main = dir.resolve("main.jar");
        Manifest m = manifest("4ee07400a592deadbeef4ee07400a592deadbeef", "2026-11-15T16:27:23Z");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "cc.jumpkick.engine.Other");
        write(main, m, "A");
        assertThat(BuildIdentity.codeIdentity(main)).isNotEqualTo(BuildIdentity.codeIdentity(one));
    }

    private static Path jar(Path at, String commit, String time, String body) throws IOException {
        write(at, manifest(commit, time), body);
        return at;
    }

    private static Manifest manifest(String commit, String time) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "cc.jumpkick.engine.EngineMain");
        m.getMainAttributes().putValue(BuildIdentity.BUILD_COMMIT_ATTRIBUTE, commit);
        m.getMainAttributes().putValue(BuildIdentity.BUILD_TIME_ATTRIBUTE, time);
        return m;
    }

    private static void write(Path at, Manifest m, String body) throws IOException {
        try (OutputStream out = Files.newOutputStream(at);
                JarOutputStream jar = new JarOutputStream(out, m)) {
            jar.putNextEntry(new JarEntry("cc/jumpkick/engine/EngineMain.class"));
            jar.write(body.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
