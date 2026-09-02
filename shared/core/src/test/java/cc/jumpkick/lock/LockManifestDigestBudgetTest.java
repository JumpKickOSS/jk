// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace digest is computed once per unchanged workspace, not once per caller.
 *
 * <p>`LockFreshness`'s own javadoc says the digest is workspace-wide, "so a per-module loop would
 * recompute the identical digest N times" — and three per-module call sites did exactly that, on top
 * of three per build, each reading every contributing manifest in full.
 */
class LockManifestDigestBudgetTest {

    @BeforeEach
    void clear() {
        LockManifestDigest.clearCache();
    }

    @Test
    void an_unchanged_workspace_is_read_once_however_many_callers_ask(@TempDir Path dir) throws IOException {
        int members = 8;
        StringBuilder root =
                new StringBuilder("group = \"g\"\nversion = \"1\"\nname = \"root\"\n[workspace]\nmodules = [");
        for (int i = 0; i < members; i++) {
            root.append(i > 0 ? ", " : "").append('"').append("m").append(i).append('"');
        }
        root.append("]\n");
        aged(dir.resolve("jk.toml"), root.toString());
        for (int i = 0; i < members; i++) {
            Path md = dir.resolve("m" + i);
            Files.createDirectories(md);
            aged(md.resolve("jk.toml"), "name = \"m" + i + "\"\ngroup.workspace = true\nversion.workspace = true\n");
        }

        String first = LockManifestDigest.compute(dir);
        long afterFirst = LockManifestDigest.manifestReads();

        for (int i = 0; i < 30; i++) {
            assertThat(LockManifestDigest.compute(dir)).isEqualTo(first);
        }

        assertThat(afterFirst)
                .as("the first computation reads the root plus each member")
                .isEqualTo(members + 1L);
        assertThat(LockManifestDigest.manifestReads())
                .as(
                        "30 further callers must add no reads; they added %d",
                        LockManifestDigest.manifestReads() - afterFirst)
                .isEqualTo(afterFirst);
    }

    @Test
    void a_member_edit_is_still_seen(@TempDir Path dir) throws IOException {
        aged(
                dir.resolve("jk.toml"),
                "group = \"g\"\nversion = \"1\"\nname = \"root\"\n[workspace]\nmodules = [\"m\"]\n");
        Path member = dir.resolve("m");
        Files.createDirectories(member);
        aged(member.resolve("jk.toml"), "name = \"m\"\ngroup.workspace = true\nversion.workspace = true\n");

        String before = LockManifestDigest.compute(dir);
        aged(member.resolve("jk.toml"), "name = \"m\"\ngroup.workspace = true\nversion.workspace = true\n# edited\n");

        assertThat(LockManifestDigest.compute(dir)).isNotEqualTo(before);
    }

    @Test
    void a_created_libraries_file_is_still_seen(@TempDir Path dir) throws IOException {
        // The case that caught the first version of the memo: jk-libs.toml did not exist on the first
        // run, so re-stat-ing only the files that had existed could never notice it appearing. An
        // absent probe is recorded as an input for exactly this.
        aged(dir.resolve("jk.toml"), "group = \"g\"\nname = \"n\"\nversion = \"1\"\n");
        String before = LockManifestDigest.compute(dir);

        aged(dir.resolve("jk-libs.toml"), "[libraries]\nfoo = \"g:a:1\"\n");

        assertThat(LockManifestDigest.compute(dir))
                .as("creating jk-libs.toml changes short-name resolution and must change the digest")
                .isNotEqualTo(before);
    }

    /** Write {@code body} and age its mtime past the settle window, so the memo may trust it. */
    private static void aged(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
    }
}
