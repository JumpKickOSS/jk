// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.git.GitBackendsTestSupport.BackendFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code describeWorktree} against a real local checkout, for every available {@link GitBackend}:
 * the CLI and JGit agree on the sha, the branch, the commit time, the dirty state and the nearest
 * tag, and both answer empty outside a repository.
 */
@Tag("integration")
class GitBackendWorktreeTest {

    private static final Instant COMMIT_TIME = Instant.parse("2026-06-01T13:47:52Z");

    private RevCommit commit(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        try (Git git = Git.init()
                .setDirectory(repoDir.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(repoDir.resolve("a.txt"), "one");
            git.add().addFilepattern("a.txt").call();
            PersonIdent ident = new PersonIdent("t", "t@e", COMMIT_TIME, ZoneOffset.UTC);
            RevCommit c = git.commit()
                    .setMessage("one")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();
            git.tag().setName("v1.2.3").setObjectId(c).setAnnotated(false).call();
            return c;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void a_clean_checkout_is_described_from_a_nested_directory(String name, BackendFactory factory, @TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        RevCommit head = commit(repo);
        Path module = Files.createDirectories(repo.resolve("services/api"));
        GitBackend backend = factory.create(tmp.resolve("gitcache"));

        GitFetcher.Worktree wt = backend.describeWorktree(module).orElseThrow();
        assertThat(wt.sha()).isEqualTo(head.getName());
        assertThat(wt.abbrev()).isEqualTo(head.getName().substring(0, 7));
        assertThat(wt.branch()).isEqualTo("main");
        assertThat(wt.commitTime()).isEqualTo(COMMIT_TIME);
        assertThat(wt.nearestTag()).contains("v1.2.3");
        assertThat(wt.dirty()).as("an empty directory is not dirt").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void an_untracked_file_and_a_detached_head_are_read(String name, BackendFactory factory, @TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        RevCommit head = commit(repo);
        GitBackend backend = factory.create(tmp.resolve("gitcache"));

        Files.writeString(repo.resolve("wip.txt"), "not committed");
        assertThat(backend.describeWorktree(repo).orElseThrow().dirty()).isTrue();

        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setName(head.getName()).call();
        }
        Files.delete(repo.resolve("wip.txt"));
        GitFetcher.Worktree detached = backend.describeWorktree(repo).orElseThrow();
        assertThat(detached.branch()).as("a detached HEAD names the commit").isEqualTo(head.getName());
        assertThat(detached.dirty()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void before_the_first_commit_there_is_nothing_to_describe(String name, BackendFactory factory, @TempDir Path tmp)
            throws Exception {
        GitBackend backend = factory.create(tmp.resolve("gitcache"));
        // A fresh repository shadows whatever checkout the temp dir itself sits in.
        Path unborn = Files.createDirectories(tmp.resolve("unborn"));
        Git.init().setDirectory(unborn.toFile()).call().close();
        assertThat(backend.describeWorktree(unborn)).isEmpty();
    }
}
