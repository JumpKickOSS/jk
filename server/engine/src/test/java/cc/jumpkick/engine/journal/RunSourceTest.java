// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.CachePlatform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a run came from: its checkout's repository and branch, its CI run, its host. */
class RunSourceTest {

    @Test
    void a_github_actions_run_names_its_provider_run_url_event_and_branch(@TempDir Path tmp) {
        Map<String, String> env = Map.of(
                "GITHUB_ACTIONS", "true",
                "GITHUB_RUN_ID", "8812",
                "GITHUB_SERVER_URL", "https://github.com",
                "GITHUB_REPOSITORY", "Acme/Shop",
                "GITHUB_EVENT_NAME", "pull_request",
                "GITHUB_HEAD_REF", "feature/cart",
                "GITHUB_REF_NAME", "42/merge");

        RunSource s = RunSource.of(tmp, env::get);

        assertThat(s.ciProvider()).isEqualTo("github-actions");
        assertThat(s.ciRunId()).isEqualTo("8812");
        assertThat(s.ciRunUrl()).isEqualTo("https://github.com/Acme/Shop/actions/runs/8812");
        assertThat(s.ciEvent()).isEqualTo("pull_request");
        assertThat(s.branch()).as("a pull request's head branch").isEqualTo("feature/cart");
        assertThat(s.repository()).isEqualTo("https://github.com/acme/shop");
        assertThat(s.host()).isEqualTo(CachePlatform.current());
        assertThat(s.cpus()).isPositive();
    }

    @Test
    void a_local_checkout_names_its_branch_and_remote_without_credentials(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("shop"));
        Path git = Files.createDirectories(repo.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("config"), """
                [core]
                \tbare = false
                [remote "upstream"]
                \turl = git@github.com:other/fork.git
                [remote "origin"]
                \turl = https://bob:s3cret@github.com/Acme/Shop.git
                """);
        Path module = Files.createDirectories(repo.resolve("app"));

        RunSource s = RunSource.of(module, name -> null);

        assertThat(s.branch()).isEqualTo("main");
        assertThat(s.repository()).isEqualTo("https://github.com/acme/shop");
        assertThat(s.ciProvider()).as("no CI variables").isNull();
        assertThat(s.toMap().toString()).doesNotContain("s3cret");
    }

    @Test
    void a_worktree_reads_its_own_branch_and_the_shared_remote(@TempDir Path tmp) throws Exception {
        Path common = Files.createDirectories(tmp.resolve("main/.git"));
        Files.writeString(common.resolve("config"), "[remote \"origin\"]\n\turl = git@gitlab.com:acme/shop.git\n");
        Path gitDir = Files.createDirectories(common.resolve("worktrees/wt"));
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/topic\n");
        Files.writeString(gitDir.resolve("commondir"), "../..\n");
        Path wt = Files.createDirectories(tmp.resolve("wt"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + gitDir + "\n");

        RunSource s = RunSource.of(wt, name -> null);

        assertThat(s.branch()).isEqualTo("topic");
        assertThat(s.repository()).isEqualTo("https://gitlab.com/acme/shop");
    }

    @Test
    void the_source_round_trips_through_the_journal() {
        RunSource s = new RunSource(
                "https://github.com/acme/shop",
                "main",
                "github-actions",
                "8812",
                "https://github.com/acme/shop/actions/runs/8812",
                "push",
                "linux-x86_64",
                16,
                64L << 30);
        BuildRecord r = BuildRecord.running(1, "build", "/ws", "g:a", "p", 1_000L, "9.9", "ci")
                .withSource(s);

        String json = Json.write(r);

        assertThat(Json.read(json).source()).isEqualTo(s);
        assertThat(json).containsPattern("\"branch\":\\s*\"main\"").contains("\"ciRunUrl\"");
        assertThat(RunSource.remote("ssh://git@host.example/a/b.git/")).isEqualTo("https://host.example/a/b");
        assertThat(List.of(Json.read(Json.write(BuildRecord.running(1, "build", "/ws", null, null, 0, "9", null)))))
                .allMatch(x -> x.source() == null);
    }
}
