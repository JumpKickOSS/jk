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
    void rebuild_flag_changes_full_fingerprint_but_not_project_scope(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        // Legacy full-flag form still differs (non-project scopes / diagnostics).
        String a = BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null);
        String b = BuildJobFingerprint.of("build", dir.toString(), true, false, false, false, null, null, null);
        assertThat(a).isNotEqualTo(b);
        // JK-1291: ofRequest for build is project-scoped — rebuild must not allow a second concurrent writer.
        String lineA = "{\"dir\":\"" + dir.toString().replace("\\", "\\\\") + "\",\"rebuild\":false}";
        String lineB = "{\"dir\":\"" + dir.toString().replace("\\", "\\\\") + "\",\"rebuild\":true}";
        assertThat(BuildJobFingerprint.ofRequest("build", lineA))
                .isEqualTo(BuildJobFingerprint.ofRequest("build", lineB));
        assertThat(BuildJobFingerprint.ofProject("build", dir.toString()))
                .isEqualTo(BuildJobFingerprint.ofRequest("build", lineA));
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
