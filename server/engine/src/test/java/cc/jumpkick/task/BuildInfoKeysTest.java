// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [build-info]}'s files key a suite or a test compile by their stable entries: the commit,
 * branch, dirty state and times move without the project moving, the name and version do
 * not.
 */
class BuildInfoKeysTest {

    static String git(String version, String sha, boolean dirty, String time) {
        return """
                git.branch=main
                git.build.time=%3$s
                git.build.version=%1$s
                git.commit.id=%2$s
                git.commit.id.abbrev=%4$s
                git.commit.time=%3$s
                git.dirty=%5$s
                """.formatted(version, sha, time, sha.substring(0, 7), dirty);
    }

    static String boot(String version, String time) {
        return """
                build.artifact=app
                build.group=com.example
                build.name=app
                build.time=%2$s
                build.version=%1$s
                """.formatted(version, time);
    }

    private static String digest(String text) {
        return BuildInfoKeys.stableDigest(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void the_commit_the_dirty_state_and_the_times_key_nothing() {
        String clean =
                digest(git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "2026-11-15T22\\:54\\:41+0000"));
        assertThat(digest(git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", true, "2026-11-15T22\\:54\\:41+0000")))
                .as("the first edit after a clean tree")
                .isEqualTo(clean);
        assertThat(digest(
                        git("1.0", "0123456789abcdef0123456789abcdef01234567", false, "2026-11-16T09\\:00\\:00+0000")))
                .as("a new commit")
                .isEqualTo(clean);
        assertThat(digest(boot("1.0", "2026-11-15T22\\:54\\:41+0000")))
                .isEqualTo(digest(boot("1.0", "2026-11-16T09\\:00\\:00+0000")));
    }

    @Test
    void a_stable_value_still_keys() {
        assertThat(digest(git("1.1", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "t")))
                .isNotEqualTo(digest(git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "t")));
        assertThat(digest(boot("1.1", "t"))).isNotEqualTo(digest(boot("1.0", "t")));
        assertThat(digest(git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "t")
                        + "git.closest.tag.name=v1\n"))
                .as("a tag is not volatile")
                .isNotEqualTo(digest(git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "t")));
    }

    @Test
    void a_test_that_names_a_volatile_value_reads_it(@TempDir Path dir) throws Exception {
        Path plain =
                Files.writeString(dir.resolve("PlainTest.java"), "class PlainTest { void t() { assert 1 == 1; } }\n");
        Path reads = Files.writeString(
                dir.resolve("InfoTest.java"),
                "class InfoTest { void t(GitProperties git) { assert git.getCommitId() != null; } }\n");
        Path key = Files.writeString(dir.resolve("KeyTest.java"), "class KeyTest { String k = \"git.commit.id\"; }\n");
        assertThat(BuildInfoKeys.namedBy(List.of(plain))).isFalse();
        assertThat(BuildInfoKeys.namedBy(List.of(plain, reads))).isTrue();
        assertThat(BuildInfoKeys.namedBy(List.of(key))).isTrue();
    }

    @Test
    void a_compile_keys_boot_build_info_by_its_stable_entries(@TempDir Path dir) throws Exception {
        Path a = tree(dir.resolve("a"), boot("1.0", "2026-11-15T22\\:54\\:41+0000"));
        Path b = tree(dir.resolve("b"), boot("1.0", "2026-11-16T09\\:00\\:00+0000"));
        Path c = tree(dir.resolve("c"), boot("1.1", "2026-11-15T22\\:54\\:41+0000"));
        assertThat(ClasspathAbi.resources(b)).as("a commit's time").isEqualTo(ClasspathAbi.resources(a));
        assertThat(ClasspathAbi.resources(c)).as("the version").isNotEqualTo(ClasspathAbi.resources(a));
    }

    @Test
    void the_tree_token_keys_the_files_by_their_stable_entries(@TempDir Path dir) throws Exception {
        List<String> files = List.of("git.properties", BuildInfoKeys.BOOT_ENTRY);
        Path a = tree(dir.resolve("a"), boot("1.0", "t1"));
        Files.writeString(
                a.resolve("git.properties"), git("1.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false, "t1"));
        Path b = tree(dir.resolve("b"), boot("1.0", "t2"));
        Files.writeString(
                b.resolve("git.properties"), git("1.0", "0123456789abcdef0123456789abcdef01234567", true, "t2"));
        assertThat(BuildInfoKeys.stableEntry(b, files)).isEqualTo(BuildInfoKeys.stableEntry(a, files));
        assertThat(ClasspathFingerprint.entry(b)).as("the bytes differ").isNotEqualTo(ClasspathFingerprint.entry(a));
        Files.writeString(b.resolve("Other.txt"), "x");
        assertThat(BuildInfoKeys.stableEntry(b, files))
                .as("any other file keys")
                .isNotEqualTo(BuildInfoKeys.stableEntry(a, files));
    }

    private static Path tree(Path root, String bootInfo) throws Exception {
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve(BuildInfoKeys.BOOT_ENTRY), bootInfo);
        Files.writeString(root.resolve("A.class"), "not really a class");
        return root;
    }
}
