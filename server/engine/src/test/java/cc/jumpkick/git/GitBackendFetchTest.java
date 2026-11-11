// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.git.GitBackendsTestSupport.BackendFactory;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** fetch/verifyLocked behaviour, run against every available {@link GitBackend}. */
@Tag("integration")
class GitBackendFetchTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void fetches_a_tag_into_a_checkout(String name, BackendFactory factory, @TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitBackend backend = factory.create(tempDir.resolve("jk-git"));

        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Tag("v1.0.0"));

        GitFetcher.Fetched fetched = backend.fetch(source, false);
        assertThat(fetched.sha()).isEqualTo(upstream.taggedSha());
        assertThat(fetched.checkoutPath().resolve("README.md")).exists();
        assertThat(Files.readString(fetched.checkoutPath().resolve("README.md")))
                .contains("v1.0.0");
    }

    /** A checkout a cancel or timeout cut short has no completion mark: the next fetch rebuilds it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void an_interrupted_checkout_is_rebuilt_not_served(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitBackend backend = factory.create(tempDir.resolve("jk-git"));
        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Tag("v1.0.0"));
        Path checkout = backend.fetch(source, false).checkoutPath();

        // What a killed checkout leaves: the directory, part of its files, and no mark.
        Files.delete(checkout.resolveSibling(checkout.getFileName() + ".complete"));
        Files.delete(checkout.resolve("README.md"));

        GitFetcher.Fetched again = backend.fetch(source, false);
        assertThat(again.checkoutPath().resolve("README.md")).exists();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void second_fetch_uses_cache(String name, BackendFactory factory, @TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitBackend backend = factory.create(tempDir.resolve("jk-git"));
        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Tag("v1.0.0"));

        GitFetcher.Fetched first = backend.fetch(source, false);
        // Backdate the checkout a clear minute, so ANY rewrite by the second fetch moves the mtime.
        // The `Thread.sleep(20)` this replaces was betting that 20ms exceeds the filesystem's mtime
        // granularity — true on ext4, not on every filesystem, and not a statement of intent
        // either way.
        long firstMtime = System.currentTimeMillis() - 60_000L;
        Files.setLastModifiedTime(first.checkoutPath(), FileTime.fromMillis(firstMtime));
        GitFetcher.Fetched second = backend.fetch(source, false);
        assertThat(second.checkoutPath()).isEqualTo(first.checkoutPath());
        assertThat(Files.getLastModifiedTime(second.checkoutPath()).toMillis()).isEqualTo(firstMtime);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void rev_spec_resolves_to_an_explicit_sha(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Rev(upstream.taggedSha()));

        GitFetcher.Fetched fetched = factory.create(tempDir.resolve("jk-git")).fetch(source, false);
        assertThat(fetched.sha()).isEqualTo(upstream.taggedSha());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void verify_locked_detects_tag_rewrite(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitBackend backend = factory.create(tempDir.resolve("jk-git"));
        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Tag("v1.0.0"));

        // Initial fetch to populate the bare clone.
        backend.fetch(source, false);
        String firstSha = upstream.taggedSha();

        // Rewrite the tag upstream to a new commit.
        String secondSha = upstream.commitAndRetag("second commit\n", "v1.0.0", true);
        assertThat(secondSha).isNotEqualTo(firstSha);

        // A later lock is a new request, so its fetcher fetches again.
        GitBackend relock = factory.create(tempDir.resolve("jk-git"));
        assertThatThrownBy(() -> relock.verifyLocked(source, firstSha))
                .isInstanceOf(GitFetcher.TagRewriteException.class)
                .hasMessageContaining(firstSha)
                .hasMessageContaining(secondSha);
    }

    /** One request verifies, fetches and resolves a source with a single fetch of its mirror. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void a_mirror_is_fetched_once_per_request(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        String branch;
        try (Git git = Git.open(upstream.workTree().toFile())) {
            branch = Objects.requireNonNull(git.getRepository().getBranch());
        }
        String url = upstream.workTree().toUri().toString();
        GitSource source = GitSource.of(url, url, new GitRefSpec.Branch(branch));
        factory.create(tempDir.resolve("jk-git")).fetch(source, false); // an earlier request's clone

        GitBackend request = factory.create(tempDir.resolve("jk-git"));
        request.verifyLocked(source, upstream.taggedSha());
        // The remote is gone: every further network fetch in this request would fail.
        Files.move(upstream.workTree(), tempDir.resolve("gone"));
        assertThat(request.fetch(source, false).sha()).isEqualTo(upstream.taggedSha());
        assertThat(request.resolveRef(source).sha()).isEqualTo(upstream.taggedSha());
    }

    /** A rev the mirror already holds cannot move, so resolving it needs no network. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void a_rev_the_mirror_holds_is_not_fetched(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        String url = upstream.workTree().toUri().toString();
        GitSource source = GitSource.of(url, url, new GitRefSpec.Rev(upstream.taggedSha()));
        factory.create(tempDir.resolve("jk-git")).fetch(source, false);

        Files.move(upstream.workTree(), tempDir.resolve("gone"));
        GitFetcher.Fetched again = factory.create(tempDir.resolve("jk-git")).fetch(source, false);
        assertThat(again.sha()).isEqualTo(upstream.taggedSha());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cc.jumpkick.git.GitBackendsTestSupport#backends")
    void missing_ref_yields_clear_error(String name, BackendFactory factory, @TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitSource source = GitSource.of(
                upstream.workTree().toUri().toString(),
                upstream.workTree().toUri().toString(),
                new GitRefSpec.Tag("v99.0.0"));

        assertThatThrownBy(() -> factory.create(tempDir.resolve("jk-git")).fetch(source, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("v99.0.0");
    }

    // --- fixture helpers --------------------------------------------------

    private static UpstreamFixture setupUpstream(Path workDir, String tagName) throws Exception {
        Files.createDirectories(workDir);
        try (Git git = Git.init().setDirectory(workDir.toFile()).call()) {
            Path readme = workDir.resolve("README.md");
            Files.writeString(readme, "Initial " + tagName + "\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            RevCommit commit = git.commit()
                    .setMessage("Initial commit")
                    .setSign(false)
                    .setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com")
                    .call();
            git.tag().setName(tagName).setObjectId(commit).setSigned(false).call();
            return new UpstreamFixture(workDir, commit.name());
        }
    }

    record UpstreamFixture(Path workTree, String taggedSha) {
        String commitAndRetag(String content, String tagName, boolean force) throws Exception {
            try (Git git = Git.open(workTree.toFile())) {
                Files.writeString(workTree.resolve("README.md"), content);
                git.add().addFilepattern("README.md").call();
                RevCommit commit = git.commit()
                        .setMessage("update")
                        .setSign(false)
                        .setAuthor("test", "test@example.com")
                        .setCommitter("test", "test@example.com")
                        .call();
                git.tag()
                        .setName(tagName)
                        .setObjectId(commit)
                        .setForceUpdate(force)
                        .setSigned(false)
                        .call();
                return commit.name();
            }
        }
    }
}
