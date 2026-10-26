// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.ProtoJobs;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.RequestEnvironment;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The engine's job-shaped build verbs: encode a request, send it, and replay the event stream the
 * engine writes back through {@link EngineEventDecoder}. Every method here is the engine-hosted
 * twin of something {@code BuildService} would otherwise run in-process, and drives the caller's
 * listener exactly as the in-process path would — per {@code docs/contributors/architecture.md} there is no
 * in-process fallback, so a failure here is a thrown error with the engine's message, never a
 * quiet degradation.
 *
 * <p>The split is by stream shape, not by verb. {@link #buildWorkspace}, {@link #runNative},
 * {@link #runImageWorkspace} and {@link #runCompileWorkspace} all speak the workspace vocabulary
 * (many plans keyed by module dir) whatever their request type; {@link #runTest}, {@link
 * #runSingleBuild} and {@link #runInstall} all speak the single-plan one. That is why the four
 * request types can share {@code buildWorkspace}'s plumbing without any of them knowing about the
 * others.
 */
final class EngineJobs {

    private EngineJobs() {}

    /**
     * Run {@code req}'s workspace build against the engine, spawning/reconnecting as needed, and
     * drive {@code listener} exactly as the engine's {@code BuildService.buildWorkspace} would
     * in-process.
     */
    static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        Session session = SessionContext.current();
        return workspace(
                paths,
                ProtoSession.withSessionFields(
                        ProtoSession.withSession(
                                encodeWorkspaceRequest(req, session),
                                req.variant(),
                                req.clientEnv(),
                                session.jvm(),
                                // rebuild rides the session envelope: bypass jk's caches without implying
                                // refresh — verify's scratch rebuild stays CAS-local (no re-download).
                                session.config().rebuildOr(false),
                                TimelineOpts.noTimeline(),
                                session.assemblyOverride()),
                        SessionContext.current()),
                req.cache(),
                listener);
    }

    /**
     * The build-request body for {@code req} as the engine sees it (before the session envelope).
     * Session-owned facts ride from {@code session}: parallel-tests, offline/force, and the
     * resolved test selection — {@code --all}/{@code --include-tags}/{@code --exclude-tags} must
     * ride the wire or the engine falls back to each module's {@code [test]} excludes and a
     * widened tier is silently served from the unit-tier stamp.
     */
    /** The session's {@code --test-failures} override as the wire spells it; null for the modules' own. */
    private static @Nullable String testFailures(Session session) {
        return session.testFailures() == null ? null : session.testFailures().wireName();
    }

    static String encodeWorkspaceRequest(WorkspaceRequest req, Session session) {
        WorkspaceSpec spec = req.spec();
        Map<String, String> graalHomes = new LinkedHashMap<>();
        if (spec != null) spec.graalByDir().forEach((dir, home) -> graalHomes.put(dir.toString(), home.toString()));
        String workspaceTarget = spec != null && spec.target() != WorkspaceTarget.PACKAGE
                ? spec.target().name().toLowerCase(Locale.ROOT)
                : null;
        return ProtoSession.withOrigin(
                new BuildRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.jdksDir() != null ? req.jdksDir().toString() : null,
                                req.workers(),
                                req.profile(),
                                req.skipTests(),
                                req.verbose(),
                                req.maxModuleConcurrency(),
                                session.parallelTests(),
                                session.offline(),
                                session.force(),
                                req.freshenLock(),
                                req.ephemeralActions(),
                                req.testOnly(),
                                req.dirtyHint() == null
                                        ? null
                                        : req.dirtyHint().stream()
                                                .map(Object::toString)
                                                .sorted()
                                                .toList(),
                                session.testSelection(),
                                ProtoJobs.debugJvmSpelling(session.debugJvm()),
                                Objects.requireNonNullElse(req.modules(), List.of()),
                                req.keepGoing(),
                                workspaceTarget,
                                graalHomes,
                                spec == null ? null : spec.tail(),
                                RequestEnvironment.trigger(),
                                RequestEnvironment.progressMode(),
                                session.coverage(),
                                testFailures(session),
                                session.skipNode())
                        .encode(),
                null,
                RequestEnvironment.session());
    }

    /**
     * The plain session envelope every job-shaped request rides: variant selection, client env,
     * worker-JVM tuning, rebuild, the {@code --no-timeline} preference, and the toolchain
     * selection. An empty envelope attaches nothing, so an unadorned request is byte-identical to
     * the bare body. Shared with the resolve adapter: a lock's resolve reads the caller's {@code
     * JK_REPO_*} credentials from the same envelope a build does.
     */
    static String envelope(String body) {
        Session session = SessionContext.current();
        return ProtoSession.withSessionFields(
                ProtoSession.withSession(
                        body,
                        session.variant(),
                        session.clientEnv(),
                        session.jvm(),
                        session.config().rebuildOr(false),
                        TimelineOpts.noTimeline()),
                SessionContext.current());
    }

    /** Send {@code requestLine} and replay the workspace event stream into {@code listener}. */
    private static WorkspaceResult workspace(
            EnginePaths.Paths paths, String requestLine, Path cache, WorkspaceBuildListener listener)
            throws IOException {
        return EngineWire.stream(
                paths,
                requestLine,
                (reader, ch) -> EngineEventDecoder.streamWorkspaceEvents(reader, listener, cache, ch));
    }
}
