// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortablePathTest {

    @BeforeEach
    void fresh() {
        PortablePath.forget();
    }

    @Test
    void a_file_under_a_module_is_keyed_relative_to_its_manifest(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("ws/api"));
        Files.writeString(module.resolve(ManifestPaths.MANIFEST), "[project]\nname='api'\n");
        Path src = Files.createDirectories(module.resolve("src/main/java"));
        Path file = Files.writeString(src.resolve("A.java"), "");
        assertThat(PortablePath.of(file)).isEqualTo("src/main/java/A.java");
    }

    @Test
    void a_file_under_no_manifest_of_its_own_is_keyed_by_the_nearest_root_above(@TempDir Path tmp) throws Exception {
        // The nearest jk.toml above the file is the root the key is relative to — never absolute,
        // never machine-named. The test plants that root itself: where a @TempDir lives is not
        // the test's business.
        Files.writeString(tmp.resolve(ManifestPaths.MANIFEST), "[project]\nname='outer'\n");
        Path dir = Files.createDirectories(tmp.resolve("store/blobs"));
        Path file = Files.writeString(dir.resolve("abc.jar"), "");
        String key = PortablePath.of(file);
        assertThat(key).endsWith("store/blobs/abc.jar");
        assertThat(Path.of(key).isAbsolute()).isFalse();
    }

    @Test
    void a_manifest_that_appears_after_the_first_lookup_is_seen_once_the_memo_ages(@TempDir Path tmp) throws Exception {
        // jk new under a resident engine, a generated module, a branch switch: the directory was
        // asked about before its jk.toml existed. A process-lifetime memo kept keying the file
        // against the outer root for the rest of the engine's life — a key no fresh engine
        // reproduces, so a spurious miss on the next restart and a hit only this engine could see.
        Files.writeString(tmp.resolve(ManifestPaths.MANIFEST), "[project]\nname='outer'\n");
        Path module = Files.createDirectories(tmp.resolve("ws/new-module"));
        Path src = Files.createDirectories(module.resolve("src"));
        Path file = Files.writeString(src.resolve("B.java"), "");
        String outer = PortablePath.of(file);
        assertThat(outer).endsWith("ws/new-module/src/B.java");

        Files.writeString(module.resolve(ManifestPaths.MANIFEST), "[project]\nname='new-module'\n");
        assertThat(PortablePath.of(file))
                .as("inside the TTL the memo still answers")
                .isEqualTo(outer);
        PortablePath.expire();
        assertThat(PortablePath.of(file)).isEqualTo("src/B.java");
    }
}
