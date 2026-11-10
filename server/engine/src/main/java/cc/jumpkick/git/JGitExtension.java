// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.util.GitProxy;
import cc.jumpkick.util.GitUrl;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * Pure-Java {@link GitBackend} backed by JGit. Always available; the fallback when no {@code git}
 * command is found. Runs in-process in the engine JVM; its http(s) connections take the proxy jk
 * chose for the remote ({@link JGitProxyRoute}), as a {@code git} child's do.
 */
public final class JGitExtension implements GitBackend {

    @Override
    public String id() {
        return "jgit";
    }

    private final Path gitRoot;
    private final ForgeGitCredentials credentials;
    private final GitProxy proxy;
    static final Pattern DESCRIBE_SUFFIX = Pattern.compile("^(.*)-\\d+-g[0-9a-fA-F]+$");

    public JGitExtension(Path gitRoot, ForgeGitCredentials credentials, GitProxy proxy) {
        this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    /** Build a per-URL credentials provider from the resolved forge token, or null for anonymous. */
    private @Nullable CredentialsProvider providerFor(String url) {
        String[] c = credentials.resolveCredentials(url);
        if (c == null || c[0] == null || c[0].isBlank()) return null;
        return new UsernamePasswordCredentialsProvider(c[0], c[1] != null ? c[1] : "");
    }

    @Override
    public GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException {
        return JGitProxyRoute.call(proxy, () -> lsRemote(source));
    }

    private GitFetcher.RemoteRefs lsRemote(GitSource source) throws IOException {
        CredentialsProvider creds = providerFor(source.canonicalUrl());
        try {
            // No setHeads/setTags filter: those exclude the symbolic HEAD. We want every ref and
            // pick out refs/tags/* + HEAD ourselves.
            var cmd = Git.lsRemoteRepository().setRemote(source.canonicalUrl());
            if (creds != null) cmd.setCredentialsProvider(creds);
            List<String> tags = new ArrayList<>();
            String head = null;
            for (Ref r : cmd.call()) {
                String name = r.getName();
                if (name.startsWith("refs/tags/")) {
                    if (name.endsWith("^{}")) continue; // peeled duplicate of an annotated tag
                    tags.add(name.substring("refs/tags/".length()));
                } else if ("HEAD".equals(name) && r.getObjectId() != null) {
                    head = r.getObjectId().getName();
                }
            }
            return new GitFetcher.RemoteRefs(List.copyOf(tags), head);
        } catch (GitAPIException e) {
            throw new IOException("git ls-remote failed: " + e.getMessage(), e);
        }
    }

    @Override
    public GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Path bareDir = ensureBareClone(source);
        String sha = resolveRefSha(source, bareDir);
        return new GitFetcher.Fetched(sha, ensureCheckout(source, bareDir, sha, noCache));
    }

    @Override
    public void verifyLocked(GitSource source, String expected) throws IOException {
        String actual = resolveRefSha(source, ensureBareClone(source));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new GitFetcher.TagRewriteException(source, expected, actual);
        }
    }

    @Override
    public GitFetcher.RefInfo resolveRef(GitSource source) throws IOException {
        Path bareDir = ensureBareClone(source);
        String sha = resolveRefSha(source, bareDir);
        try (Git git = Git.open(bareDir.toFile());
                RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(ObjectId.fromString(sha));
            return new GitFetcher.RefInfo(
                    sha, Instant.ofEpochSecond(c.getCommitTime()), describeNearestTag(git, ObjectId.fromString(sha)));
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitFetcher.Worktree> describeWorktree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return Optional.empty();
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir.toFile());
        if (builder.getGitDir() == null) return Optional.empty();
        try (Repository repo = builder.build();
                Git git = new Git(repo);
                RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) return Optional.empty(); // an unborn HEAD
            RevCommit commit = walk.parseCommit(head);
            String branch = repo.getBranch(); // the sha itself when detached
            boolean dirty = !git.status().call().isClean();
            return Optional.of(new GitFetcher.Worktree(
                    head.getName(),
                    branch == null ? head.getName() : branch,
                    Instant.ofEpochSecond(commit.getCommitTime()),
                    dirty,
                    describeNearestTag(git, head)));
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    Path ensureBareClone(GitSource source) throws IOException {
        return JGitProxyRoute.call(proxy, () -> cloneOrFetch(source));
    }

    private Path cloneOrFetch(GitSource source) throws IOException {
        CredentialsProvider creds = providerFor(source.canonicalUrl());
        Path bareDir = gitRoot.resolve("db").resolve(GitUrl.canonicalHash(source.canonicalUrl()));
        return GitCacheDirs.locked(bareDir, () -> {
            if (GitCacheDirs.complete(bareDir)) {
                try (Git git = Git.open(bareDir.toFile())) {
                    var f = git.fetch()
                            .setRemote("origin")
                            .setRemoveDeletedRefs(true)
                            .setTagOpt(TagOpt.FETCH_TAGS)
                            .setRefSpecs(
                                    new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));
                    if (creds != null) f.setCredentialsProvider(creds);
                    f.call();
                } catch (GitAPIException | RuntimeException e) {
                    throw new IOException("git fetch failed: " + e.getMessage(), e);
                }
                return bareDir;
            }
            try {
                new URIish(source.canonicalUrl());
            } catch (URISyntaxException e) {
                throw new IOException("invalid URL", e);
            }
            return GitCacheDirs.publish(bareDir, false, staging -> {
                try {
                    var clone = Git.cloneRepository()
                            .setBare(true)
                            .setURI(source.canonicalUrl())
                            .setDirectory(staging.toFile());
                    // Explicit tag entries request a shallow clone (depth 1) to save bandwidth.
                    // URL-embedded refs and all other ref types use a full clone.
                    if (source.shallow()) clone.setDepth(1);
                    if (creds != null) clone.setCredentialsProvider(creds);
                    clone.call().close();
                } catch (GitAPIException | RuntimeException e) {
                    throw new IOException("git clone failed: " + e.getMessage(), e);
                }
            });
        });
    }

    static String resolveRefSha(GitSource source, Path bareDir) throws IOException {
        GitRefSpec ref = source.ref();
        if (ref instanceof GitRefSpec.Rev rev) {
            try (Git git = Git.open(bareDir.toFile())) {
                ObjectId id = git.getRepository().resolve(rev.sha());
                if (id == null) throw new IOException("rev " + rev.sha() + " not found");
                return id.getName();
            }
        }
        String refName = ref instanceof GitRefSpec.Tag t
                ? "refs/tags/" + t.name()
                : "refs/heads/" + ((GitRefSpec.Branch) ref).name();
        try (Git git = Git.open(bareDir.toFile())) {
            Repository repo = git.getRepository();
            Ref r = repo.findRef(refName);
            if (r == null) throw new IOException("ref " + refName + " not found");
            ObjectId peeled = r.getPeeledObjectId();
            ObjectId target = peeled != null ? peeled : r.getObjectId();
            // A symbolic ref that points at nothing resolves to no object at all.
            if (target == null) throw new IOException("ref " + refName + " resolves to no object");
            return target.getName();
        }
    }

    Path ensureCheckout(GitSource source, Path bareDir, String sha, boolean noCache) throws IOException {
        Path dir = gitRoot.resolve("co")
                .resolve(GitUrl.canonicalHash(source.canonicalUrl()))
                .resolve(sha);
        return GitCacheDirs.locked(
                dir,
                () -> GitCacheDirs.publish(dir, noCache, staging -> {
                    try (Git g = Git.cloneRepository()
                            .setURI(bareDir.toUri().toString())
                            .setDirectory(staging.toFile())
                            .setNoCheckout(true)
                            .call()) {
                        g.checkout().setName(sha).call();
                    } catch (GitAPIException | RuntimeException e) {
                        throw new IOException("checkout failed: " + e.getMessage(), e);
                    }
                }));
    }

    static Optional<String> describeNearestTag(Git git, ObjectId target) throws GitAPIException, IOException {
        String d = git.describe().setTarget(target).setTags(true).call();
        if (d == null || d.isBlank()) return Optional.empty();
        Matcher m = DESCRIBE_SUFFIX.matcher(d);
        return Optional.of(m.matches() ? m.group(1) : d);
    }

    static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
