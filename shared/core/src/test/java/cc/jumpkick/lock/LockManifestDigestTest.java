// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockManifestDigestTest {

    @Test
    void hashParts_is_order_independent_by_sorting_keys() {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put("b.toml", "x".getBytes());
        a.put("a.toml", "y".getBytes());
        Map<String, byte[]> b = new LinkedHashMap<>();
        b.put("a.toml", "y".getBytes());
        b.put("b.toml", "x".getBytes());
        assertThat(LockManifestDigest.hashParts(a)).isEqualTo(LockManifestDigest.hashParts(b));
    }

    @Test
    void hashParts_changes_when_content_changes() {
        Map<String, byte[]> a = Map.of("jk.toml", "name = \"a\"\n".getBytes());
        Map<String, byte[]> b = Map.of("jk.toml", "name = \"b\"\n".getBytes());
        assertThat(LockManifestDigest.hashParts(a)).isNotEqualTo(LockManifestDigest.hashParts(b));
    }

    @Test
    void compute_stable_for_standalone_project(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        String d1 = LockManifestDigest.compute(dir);
        String d2 = LockManifestDigest.compute(dir);
        assertThat(d1).isEqualTo(d2).hasSize(64);
    }

    @Test
    void compute_changes_when_manifest_edited(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(
                toml,
                """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        String before = LockManifestDigest.compute(dir);
        Files.writeString(
                toml,
                """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.1"
                """);
        assertThat(LockManifestDigest.compute(dir)).isNotEqualTo(before);
    }
}
