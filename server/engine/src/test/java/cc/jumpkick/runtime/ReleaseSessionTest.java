// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build is the release exactly when its tree is a clean checkout at the declared version's tag;
 * every other tree builds a snapshot.
 */
class ReleaseSessionTest {

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = GitCommand.of();
        command.addAll(List.of(args));
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true), Duration.ofMinutes(1));
        assertThat(run.exit())
                .as(String.join(" ", command) + "\n" + run.output())
                .isZero();
        return run.output().strip();
    }

    private static Path project(Path dir, String extra) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.4.7"
                %s""".formatted(extra));
        return dir;
    }

    private static Path committed(Path dir, String extra) throws Exception {
        project(dir, extra);
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Ada");
        git(dir, "config", "user.email", "ada@example.com");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "Start");
        return dir;
    }

    @Test
    void a_clean_checkout_at_the_declared_versions_tag_is_the_release(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        git(dir, "tag", "v1.4.7");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE))
                .isEqualTo(new ReleaseSession.Verdict(true, "Release 1.4.7 (tag v1.4.7)"));
    }

    @Test
    void an_annotated_tag_counts_as_a_lightweight_one(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        git(dir, "tag", "-a", "v1.4.7", "-m", "1.4.7");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE).release()).isTrue();
    }

    @Test
    void an_untagged_head_builds_a_snapshot_and_says_nothing(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE)).isEqualTo(new ReleaseSession.Verdict(false, ""));
    }

    @Test
    void the_tag_of_another_version_builds_a_snapshot(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        git(dir, "tag", "v1.4.6");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE).release()).isFalse();
    }

    @Test
    void the_tag_on_an_earlier_commit_builds_a_snapshot(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        git(dir, "tag", "v1.4.7");
        Files.writeString(dir.resolve("NOTES.md"), "after\n");
        git(dir, "add", "NOTES.md");
        git(dir, "commit", "-q", "-m", "After");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE).release()).isFalse();
    }

    @Test
    void a_tagged_tree_with_changes_builds_a_snapshot_and_says_why(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        git(dir, "tag", "v1.4.7");
        Files.writeString(dir.resolve("Untracked.java"), "class Untracked {}\n");

        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE))
                .isEqualTo(new ReleaseSession.Verdict(
                        false, "HEAD is v1.4.7 but the tree differs from it, so this build is a snapshot"));
    }

    @Test
    void a_tree_outside_git_builds_a_snapshot(@TempDir Path tmp) throws Exception {
        assertThat(ReleaseSession.verdict(project(tmp, ""), ReleaseVerb.NONE).release())
                .isFalse();
    }

    @Test
    void the_tag_follows_the_release_table(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "\n[release]\ntag = \"lib-{version}\"\n");
        git(dir, "tag", "v1.4.7");
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE).release()).isFalse();

        git(dir, "tag", "lib-1.4.7");
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE))
                .isEqualTo(new ReleaseSession.Verdict(true, "Release 1.4.7 (tag lib-1.4.7)"));
    }

    @Test
    void a_linked_worktree_at_the_tag_is_the_release(@TempDir Path tmp) throws Exception {
        Path main = committed(tmp.resolve("main"), "");
        git(main, "tag", "v1.4.7");
        Path linked = tmp.resolve("linked");
        git(main, "worktree", "add", "-q", "--detach", linked.toString(), "v1.4.7");

        assertThat(ReleaseSession.verdict(linked, ReleaseVerb.NONE).release()).isTrue();
    }

    @Test
    void a_member_answers_for_its_workspace_root(@TempDir Path tmp) throws Exception {
        Path member = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(member.resolve("jk.toml"), "name = \"m\"\nversion = \"9.9.9\"\n");
        committed(tmp, "\n[workspace]\nmodules = [\"m\"]\n");
        git(tmp, "tag", "v1.4.7");

        assertThat(ReleaseSession.verdict(member, ReleaseVerb.NONE).release()).isTrue();
    }

    @Test
    void outside_git_release_and_publish_are_releases_by_the_verb(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp, "");
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.RELEASE).release()).isTrue();
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.PUBLISH).release()).isTrue();
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.NONE).release()).isFalse();
    }

    @Test
    void inside_git_publish_needs_the_tag_and_release_cuts_it(@TempDir Path tmp) throws Exception {
        Path dir = committed(tmp, "");
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.PUBLISH).release()).isFalse();
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.RELEASE).release()).isTrue();
        git(dir, "tag", "v1.4.7");
        assertThat(ReleaseSession.verdict(dir, ReleaseVerb.PUBLISH).release()).isTrue();
    }
}
