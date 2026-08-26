// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jdk.JdkFingerprint;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // symlink creation requires Developer Mode on Windows
@Tag("integration")
class DoctorCommandTest {

    @Test
    void prunes_broken_links_in_build_tool_tree(@TempDir Path tempDir) throws Exception {
        // Synthetic maven install pointing at a missing target.
        Path mavenSlug = tempDir.resolve("maven");
        Files.createDirectories(mavenSlug);
        Path link = mavenSlug.resolve("3.9.9");
        Files.createSymbolicLink(link, tempDir.resolve("nonexistent"));

        String stdout = capture(() -> Jk.execute("doctor", "--tools-dir", tempDir.toString()));
        assertThat(stdout).containsPattern("pruned:\\s+maven 3.9.9");
        assertThat(stdout).contains("1 pruned");
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void output_json_actually_prunes_not_just_reports(@TempDir Path tempDir) throws Exception {
        // The JSON path used to only count broken links; the mutating unlink ran on the human path
        // only, so `jk doctor --output json` reported "pruned" without touching the filesystem.
        Path mavenSlug = tempDir.resolve("maven");
        Files.createDirectories(mavenSlug);
        Path link = mavenSlug.resolve("3.9.9");
        Files.createSymbolicLink(link, tempDir.resolve("nonexistent"));

        String stdout = capture(() -> Jk.execute("doctor", "--tools-dir", tempDir.toString(), "--output", "json"));

        assertThat(stdout).contains("\"pruned\":1");
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void reports_ok_for_healthy_local_install(@TempDir Path tempDir) throws Exception {
        // A real (not-link) gradle dir under the build-tools tree.
        Path home = tempDir.resolve("gradle").resolve("9.5.1");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/gradle"), "#!/bin/sh\n");

        String stdout = capture(() -> Jk.execute("doctor", "--tools-dir", tempDir.toString()));
        assertThat(stdout).containsPattern("ok:\\s+gradle 9.5.1");
    }

    /**
     * {@code --verify-linked} on a symlinked tool home: fingerprint the target, store it, read it
     * back next run, and say so when it moves.
     *
     * <p>Every assertion here was missing before JK-2467, and the flag was broken in two ways at
     * once. {@code JdkFingerprint.compute} handed the symlinked root to {@code visitFile}, so the
     * digest was SHA-256 of an empty manifest for every tool on every run; and nothing in the tree
     * ever read the {@code .fingerprint} marker back, so even a correct digest verified nothing.
     * Hence the two halves: the recorded digest is the target's (and is not the empty one), and the
     * second run compares against it.
     */
    @Test
    void verify_linked_records_the_target_digest_then_reports_drift(@TempDir Path tempDir) throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("host-maven"));
        Files.writeString(target.resolve("README"), "maven 3.9.9\n");
        Path mavenSlug = Files.createDirectories(tempDir.resolve("maven"));
        Files.createSymbolicLink(mavenSlug.resolve("3.9.9"), target);
        Path marker = mavenSlug.resolve("3.9.9.fingerprint");

        String first = verifyLinked(tempDir);
        assertThat(first).containsPattern("recorded:\\s+maven 3.9.9");
        assertThat(Files.readString(marker).strip())
                .isEqualTo(JdkFingerprint.compute(target))
                .isNotEqualTo(JdkFingerprint.EMPTY_TREE);

        // Unchanged tree: the stored digest is read back and matches.
        assertThat(verifyLinked(tempDir)).containsPattern("verified:\\s+maven 3.9.9");

        // Changed tree: drift, and the baseline is re-cut so the next run is quiet again.
        Files.writeString(target.resolve("README"), "maven 3.9.10\n");
        assertThat(verifyLinked(tempDir)).containsPattern("drifted:\\s+maven 3.9.9");
        assertThat(Files.readString(marker).strip()).isEqualTo(JdkFingerprint.compute(target));
        assertThat(verifyLinked(tempDir)).containsPattern("verified:\\s+maven 3.9.9");
    }

    /**
     * A link to a tree with no files is called out, not printed as if it were a fingerprint. This
     * is the one input that legitimately produces {@code e3b0c442…}, and it is exactly the value the
     * broken flag produced for everything — so it does not get to look like a success.
     */
    @Test
    void verify_linked_refuses_to_pass_off_an_empty_target_as_a_fingerprint(@TempDir Path tempDir) throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("hollow"));
        Path mavenSlug = Files.createDirectories(tempDir.resolve("maven"));
        Files.createSymbolicLink(mavenSlug.resolve("3.9.9"), target);

        String stdout = verifyLinked(tempDir);
        assertThat(stdout).contains("no files");
        assertThat(stdout).doesNotContain(JdkFingerprint.EMPTY_TREE.substring(0, 12));
    }

    private static String verifyLinked(Path toolsDir) {
        return capture(() -> Jk.execute("doctor", "--tools-dir", toolsDir.toString(), "--verify-linked"));
    }

    /** Strip ANSI so assertions match the text regardless of color/alignment styling. */
    private static String capture(Runnable body) {
        return TestAnsi.strip(Capture.stdout(body));
    }
}
