// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactMemoTest {

    @Test
    void jar_memo_name_strips_extension(@TempDir Path dir) {
        Path jk = ArtifactMemo.jkPath(dir, "com/foo/bar/1.0/bar-1.0.jar");
        assertThat(jk.getFileName().toString()).isEqualTo("bar-1.0.jk");
        assertThat(ArtifactMemo.jkPath(dir, "com/foo/bar/1.0/bar-1.0.pom")
                        .getFileName()
                        .toString())
                .isEqualTo("bar-1.0.pom.jk");
    }

    @Test
    void a_memo_path_never_leaves_the_store_root(@TempDir Path dir) {
        assertThatThrownBy(() -> ArtifactMemo.jkPath(dir, "../outside/bar-1.0.jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void round_trip_and_mtime_fast_path(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("bar-1.0.jar");
        Files.writeString(jar, "payload");
        String hex = Hashing.sha256Hex(jar);
        Path jk = dir.resolve("bar-1.0.jk");
        assertThat(ArtifactMemo.verify(jar, jk, "com.foo:bar:1.0", hex)).isTrue();
        ArtifactMemo memo = ArtifactMemo.read(jk).orElseThrow();
        assertThat(memo.coordinate()).isEqualTo("com.foo:bar:1.0");
        assertThat(memo.sha256()).isEqualTo(hex);
        assertThat(memo.size()).isEqualTo(Files.size(jar));
        assertThat(Files.readString(jk).strip().split("\n")).hasSize(4);
        assertThat(ArtifactMemo.verify(jar, jk, "com.foo:bar:1.0", hex)).isTrue();
        assertThat(ArtifactMemo.verify(jar, jk, "com.foo:bar:1.0", "0".repeat(64)))
                .isFalse();
    }

    @Test
    void the_packaging_engine_is_a_fifth_line_that_survives_a_re_verify(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("bar-1.0.jar");
        Files.writeString(jar, "payload");
        String hex = Hashing.sha256Hex(jar);
        String engine = "AB12" + "0".repeat(60);
        Path jk = dir.resolve("bar-1.0.jk");
        ArtifactMemo.ofBlob(jar, "com.foo:bar:1.0", hex, engine).write(jk);

        ArtifactMemo memo = ArtifactMemo.read(jk).orElseThrow();
        assertThat(memo.packagedBy()).isEqualTo(engine.toLowerCase());
        assertThat(Files.readString(jk).strip().split("\n")).hasSize(5);

        // A touched blob re-hashes and rewrites the memo; the packager is not lost on the way.
        Files.setLastModifiedTime(jar, FileTime.fromMillis(memo.mtimeMillis() + 5_000));
        assertThat(ArtifactMemo.verify(jar, jk, "com.foo:bar:1.0", hex)).isTrue();
        assertThat(ArtifactMemo.read(jk).orElseThrow().packagedBy()).isEqualTo(engine.toLowerCase());

        // A memo without the line records no packager, and writes none.
        ArtifactMemo.ofBlob(jar, "com.foo:bar:1.0", hex).write(jk);
        assertThat(ArtifactMemo.read(jk).orElseThrow().packagedBy()).isNull();
        assertThat(Files.readString(jk).strip().split("\n")).hasSize(4);
    }
}
