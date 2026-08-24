// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
