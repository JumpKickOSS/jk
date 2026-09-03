// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
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
        // OfRequest for build is project-scoped — rebuild must not allow a second concurrent writer.
        String lineA = "{\"dir\":" + Jsonl.quote(dir.toString()) + ",\"rebuild\":false}";
        String lineB = "{\"dir\":" + Jsonl.quote(dir.toString()) + ",\"rebuild\":true}";
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

    /**
     * A build and a test on one workspace write the same compile outputs and the same test
     * sandboxes (and the root after-build scripts reclaim disk under them), so they take one slot:
     * the second is refused at admission instead of racing the first on target/.
     */
    @Test
    void build_like_kinds_share_one_slot_per_dir(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        String build = BuildJobFingerprint.ofRequest("build", "{\"dir\":" + Jsonl.quote(dir.toString()) + "}");
        String test = BuildJobFingerprint.ofRequest("test", "{\"dir\":" + Jsonl.quote(dir.toString()) + "}");
        assertThat(test).isEqualTo(build);
        // The full-flag form (non-build-like kinds) still tells kinds apart.
        assertThat(BuildJobFingerprint.of("build", dir.toString(), false, false, false, false, null, null, null))
                .isNotEqualTo(
                        BuildJobFingerprint.of("test", dir.toString(), false, false, false, false, null, null, null));
    }

    @Test
    void native_and_image_are_project_scoped_like_build(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        // Flag churn must not open a second concurrent slot for build-like kinds.
        String nativeA = BuildJobFingerprint.ofRequest(
                "native",
                "{\"type\":\"native-request\",\"dir\":" + Jsonl.quote(dir.toString()) + ",\"rebuild\":false}");
        String nativeB = BuildJobFingerprint.ofRequest(
                "native", "{\"type\":\"native-request\",\"dir\":" + Jsonl.quote(dir.toString()) + ",\"rebuild\":true}");
        assertThat(nativeA).isEqualTo(nativeB);
        assertThat(BuildJobFingerprint.ofProject("native", dir.toString())).isEqualTo(nativeA);

        String image = BuildJobFingerprint.ofProject("image", dir.toString());
        String compile = BuildJobFingerprint.ofProject("compile", dir.toString());
        assertThat(image).isEqualTo(compile);
        assertThat(image).isEqualTo(BuildJobFingerprint.ofProject("build", dir.toString()));
    }
}
