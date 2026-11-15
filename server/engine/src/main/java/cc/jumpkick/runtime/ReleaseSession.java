// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.config.ReleaseVersion;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.git.HeadTag;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a request builds the release or a snapshot: the one owner of that decision, made for every
 * verb from the verb ({@link ReleaseVerb}) and the tree.
 *
 * <ul>
 *   <li>Inside git, a build is the release of the version {@code jk.toml} declares exactly when the
 *       working tree is clean and HEAD carries the tag {@code jk release} writes for it ({@code
 *       [release] tag}, {@code v<version>} by default; annotated or lightweight). {@code jk
 *       release} creates that state, so its own request is the release it is cutting; {@code jk
 *       publish} requires it.
 *   <li>Outside git (a source archive), {@code jk release} and {@code jk publish} are releases by
 *       the verb itself and every other verb builds a snapshot.
 * </ul>
 *
 * <p>The tag is read from the refs, with no git process; only a HEAD that carries it pays for the
 * clean-tree check, and that is the worktree read the build stamps {@code Build-Commit} and {@code
 * git.properties} from, once per request.
 */
public final class ReleaseSession {

    private static final Object VERDICTS = new Object();

    private ReleaseSession() {}

    /**
     * What a request builds.
     *
     * @param release the build ships the declared version
     * @param line one line saying so for a release ({@code Release 0.51.1 (tag v0.51.1)}), or why a
     *     snapshot whose HEAD carries the tag is one; empty for an ordinary snapshot and for the
     *     release {@code jk release} is cutting
     */
    public record Verdict(boolean release, String line) {

        static final Verdict SNAPSHOT = new Verdict(false, "");
    }

    /**
     * The session a request line runs under: {@link ProtoSession#sessionOf}, a release when the verb
     * and the tree make it one. Every engine verb that plans, builds or forecasts takes its session
     * from here. A request that already reads manifests as declared ({@link
     * SnapshotQualifier#asDeclared}) keeps that.
     */
    public static Session sessionOf(String request, Session.CancelToken cancel) {
        Session session = ProtoSession.sessionOf(request, cancel);
        if (session.release()) return session;
        return verdict(session).release() ? session.withRelease(true) : session;
    }

    /** {@link #verdict(Path, ReleaseVerb)} under {@code session}, so the worktree read is the request's own. */
    public static Verdict verdict(Session session) {
        try {
            return SessionContext.where(session, () -> verdict(session.workingDir(), session.releaseVerb()));
        } catch (Exception e) {
            return Verdict.SNAPSHOT;
        }
    }

    /**
     * What {@code verb} builds of the project at {@code dir}; a snapshot whenever a fact cannot be
     * read. Decided once per request, workspace root and verb.
     */
    public static Verdict verdict(Path dir, ReleaseVerb verb) throws IOException {
        Path root = WorkspaceScan.owningRoot(dir).orElse(dir);
        Map<String, Verdict> memo = RequestScope.current().get(VERDICTS, k -> new ConcurrentHashMap<>());
        String key = root + "\0" + verb;
        Verdict known = memo.get(key);
        if (known != null) return known;
        Verdict decided = decide(root, verb);
        memo.put(key, decided);
        return decided;
    }

    private static Verdict decide(Path root, ReleaseVerb verb) throws IOException {
        Path checkout = PlannerBuildInfo.checkoutRoot(root);
        if (checkout == null) return verb == ReleaseVerb.NONE ? Verdict.SNAPSHOT : new Verdict(true, "");
        if (verb == ReleaseVerb.RELEASE) return new Verdict(true, "");
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return Verdict.SNAPSHOT;
        String version = ReleaseVersion.declared(Files.readString(manifest, StandardCharsets.UTF_8))
                .orElse(null);
        if (version == null) return Verdict.SNAPSHOT;
        String tag;
        try {
            tag = ReleaseTag.render(JkBuildParser.releaseTable(manifest).tag(), version);
        } catch (RuntimeException unparsable) {
            return Verdict.SNAPSHOT;
        }
        if (!HeadTag.carries(checkout, tag)) return Verdict.SNAPSHOT;
        Optional<GitFetcher.Worktree> worktree = PlannerBuildInfo.worktree(root);
        if (worktree.isEmpty()) return Verdict.SNAPSHOT;
        if (worktree.get().dirty()) {
            return new Verdict(false, "HEAD is " + tag + " but the tree differs from it, so this build is a snapshot");
        }
        return new Verdict(true, "Release " + version + " (tag " + tag + ")");
    }
}
