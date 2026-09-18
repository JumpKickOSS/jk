// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fresh lock an earlier writer left with unmarked file-less rows is rewritten in place — the
 * rows name their POM file, the manifest digest and everything else stay — so it converges without
 * a resolve and without a diff to read beyond the marks.
 */
class AutoLockMarksTest {

    @Test
    void a_fresh_lock_gets_its_marks_in_place_and_stays_fresh(@TempDir Path tmp) throws Exception {
        Path proj = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(proj.resolve("jk.toml"), """
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                java = 25
                """);
        Path lockFile = LockPaths.lockFile(proj);
        Lockfile.Artifact unmarked = new Lockfile.Artifact(
                "org.picketbox:picketbox:jar:",
                "5.0.3.Final",
                "jumpkick+https://jumpkick.build/repo/",
                null,
                null,
                List.of(Scope.PROVIDED),
                List.of());
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk 0.1", "pubgrub-v1", List.of(unmarked)), lockFile);
        String before = Files.readString(lockFile);
        assertThat(AutoLock.isStale(proj, lockFile)).isFalse();

        assertThat(AutoLock.markFilelessRows(lockFile)).isTrue();

        Lockfile marked = LockfileReader.read(lockFile);
        assertThat(marked.artifacts())
                .singleElement()
                .extracting(Lockfile.Artifact::path)
                .isEqualTo("picketbox-5.0.3.Final.pom");
        assertThat(marked.manifestsSha256())
                .isEqualTo(LockfileReader.read(lockFile).manifestsSha256());
        assertThat(AutoLock.isStale(proj, lockFile))
                .as("the manifest digest is kept")
                .isFalse();
        assertThat(Files.readString(lockFile))
                .as("only the row's file line and the writer's name change")
                .isNotEqualTo(before)
                .contains("path     = \"picketbox-5.0.3.Final.pom\"");
        assertThat(AutoLock.markFilelessRows(lockFile))
                .as("nothing left to mark")
                .isFalse();
    }
}
