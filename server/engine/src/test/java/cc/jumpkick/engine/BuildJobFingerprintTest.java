// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildJobFingerprintTest {

    @Test
    void same_dir_and_flags_match(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        String a = BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null);
        String b = BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void rebuild_flag_changes_fingerprint(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        String a = BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null);
        String b = BuildJobFingerprint.of("build", dir.toString(), true, false, false, false, null, null, null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void different_paths_differ(@TempDir Path root) throws Exception {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        assertThat(BuildJobFingerprint.of("build", a.toString(), false, false, false, false, null, null, null))
                .isNotEqualTo(
                        BuildJobFingerprint.of("build", b.toString(), false, false, false, false, null, null, null));
    }

    @Test
    void build_and_test_kinds_differ(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        assertThat(BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null))
                .isNotEqualTo(
                        BuildJobFingerprint.of("test", dir.toString(), false, false, false, false, null, null, null));
    }
}
