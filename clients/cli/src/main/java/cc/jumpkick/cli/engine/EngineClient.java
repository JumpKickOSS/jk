// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;

/**
 * CLI-side counterpart to {@link cc.jumpkick.engine.EngineServer}: connects, ensures a live
 * version-matched engine, and exposes hosted verbs. Spawn/takeover/AOT live in {@link
 * EngineSpawn}; request records in {@link EngineRequests}; fat hosted bodies in {@link
 * EngineHosted}. This type stays the one command-facing facade (scoreboard 800–1,200) so adding
 * {@code jk quux} does not scatter imports across six collaborators.
 */
public final class EngineClient {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    private static final int SOCKET_TIMEOUT_MILLIS = EngineWire.SOCKET_TIMEOUT_MILLIS;

    /**
     * After force-stop / hard-kill, wait this long for the OS process to exit before escalating.
     * Keeps the next client from racing a half-dead generation.
     */
    private static final Duration STOP_DEATH_WAIT = Duration.ofMillis(1_500);

    private EngineClient() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the engine. */
    public record Handshake(String version, long pid, long startedAtMillis, boolean draining, String buildId) {}

    /**
     * {@code jk engine status} snapshot. Memory fields use {@code -1} when unknown; http fields
     * report embedded server URL/error; {@code aotTrainingPid} is visibility-only.
     */
    public record Status(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activeBuildPlans,
            boolean draining,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            long aotTrainingPid,
            String httpUrl,
            String httpError,
            /** MCP JSON-RPC endpoint when HTTP is up ({@code httpUrl + "/mcp"}), else null. */
            String mcpUrl) {

        /** {@code true} when the engine has an {@code [http]} table — serving or bind-failed. */
        public boolean httpEnabled() {
            return httpUrl != null || httpError != null;
        }
    }

    /**
     * Connect, ping, and get {@code pong} back — the engine-existence check per {@code docs/architecture.md}
     * (never trust a pidfile alone). {@code false} for anything from "nothing is listening" to "it
     * answered something unexpected."
     */
    public static boolean ping(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String reply = exchange(ch, ProtoLifecycle.ping());
            return EngineProtocol.PONG.equals(EngineProtocol.typeOf(reply));
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and perform the {@code hello}/{@code hello-ack} handshake; empty if unreachable. */
    public static Optional<Handshake> handshake(Path socket, String clientVersion) {
        try (SocketChannel ch = connect(socket)) {
            String ack = exchange(ch, ProtoLifecycle.hello(clientVersion));
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            // Protocol-zero's teeth: an engine speaking a NEWER protocol than this client is not
            // usable — treat it as unreachable so the ensure path elects/starts a matching one
            // (which the newer engine's takeover logic then arbitrates).
            if (Jsonl.intValue(ack, "proto", EngineProtocol.PROTOCOL) > EngineProtocol.PROTOCOL) {
                return Optional.empty();
            }
            String ackBuildId = Jsonl.str(ack, "buildId");
            return Optional.of(new Handshake(
                    Jsonl.str(ack, "version"),
                    Jsonl.longValue(ack, "pid", -1),
                    Jsonl.longValue(ack, "startedAt", -1),
                    Jsonl.bool(ack, "draining", false),
                    ackBuildId == null ? "" : ackBuildId));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a socket connection can be opened at all, regardless of protocol behavior — for host
     * checks (e.g. {@code jk doctor}) that must tell "nothing is listening" (a WARN — lazy-start
     * handles it) apart from "something is listening but not answering" (a wedged engine, a FAIL).
     * {@link #status} alone cannot make that distinction: it returns empty for both.
     */
    public static boolean reachable(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and request a status snapshot; empty if no engine is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            exchange(
                    ch,
                    ProtoLifecycle.hello(cc.jumpkick.cli.Jk.VERSION, "probe")); // handshake first, response discarded
            String ack = exchange(ch, ProtoLifecycle.statusRequest());
            if (!EngineProtocol.STATUS_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            String httpUrl = Jsonl.str(ack, "httpUrl");
            String mcpUrl = Jsonl.str(ack, "mcpUrl"); // null = MCP disabled
            return Optional.of(new Status(
                    Jsonl.str(ack, "version"),
                    Jsonl.longValue(ack, "pid", -1),
                    Jsonl.longValue(ack, "startedAt", -1),
                    Jsonl.intValue(ack, "activeRequests", -1),
                    Jsonl.intValue(ack, "activeBuildPlans", 0),
                    Jsonl.bool(ack, "draining", false),
                    Jsonl.longValue(ack, "heapUsedBytes", -1),
                    Jsonl.longValue(ack, "heapCommittedBytes", -1),
                    Jsonl.longValue(ack, "heapMaxBytes", -1),
                    Jsonl.longValue(ack, "rssBytes", -1),
                    Jsonl.longValue(ack, "aotTrainingPid", -1),
                    httpUrl,
                    Jsonl.str(ack, "httpError"),
                    mcpUrl));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Ask a reachable engine to shut down gracefully; {@code true} if one was reached and acknowledged
     * (or was already not running — stopping a non-running engine is not an error), {@code false} if
     * one was reachable but didn't acknowledge cleanly.
     */
    public static boolean stop(Path socket) {
        SocketChannel ch;
        try {
            ch = connect(socket);
        } catch (IOException e) {
            return true; // nothing reachable — a no-op "stop" is success
        }
        try (ch) {
            String bye = exchange(ch, ProtoLifecycle.shutdown());
            return EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
        } catch (IOException e) {
            return false; // reachable but didn't behave — a real problem, not "already stopped"
        }
    }

    /**
     * Schedule a graceful drain: the engine refuses new jobs and exits cleanly once in-flight jobs
     * finish. Returns the in-flight job count at the moment of the request (0 → the engine is exiting
     * now), or {@code -1} when nothing was reachable (a no-op stop).
     */
    public static int drain(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String bye = exchange(ch, ProtoLifecycle.shutdown(false));
            if (!EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye))) return -1;
            return Jsonl.intValue(bye, "plans", 0);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Force an immediate shutdown: the engine exits now via its clean-exit path (abandoning in-flight
     * job connections but still assembling the AOT cache). {@code true} if acknowledged or nothing was
     * running; {@code false} if reachable but unresponsive (caller may {@link #hardKill} as fallback).
     *
     * <p>When a pid file is present for {@code socket}, waits for that process to actually die
     * so the next {@link #ensureRunning} does not race a half-stopped generation.
     */
    public static boolean forceStop(Path socket) {
        long pid = readPidForSocket(socket);
        SocketChannel ch;
        try {
            ch = connect(socket);
        } catch (IOException e) {
            // Nothing accepting — still wait out a leftover pid if the file is stale-but-alive.
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            return true;
        }
        try (ch) {
            String bye = exchange(ch, ProtoLifecycle.shutdown(true));
            boolean ok = EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            else if (!ok) {
                // bye missing but we connected — best-effort: try pid from a late status is gone;
                // nothing else to wait on.
            }
            return ok;
        } catch (IOException e) {
            if (pid > 0) waitForDeathOrKill(pid, STOP_DEATH_WAIT);
            return false;
        }
    }

    /**
     * Last-resort SIGTERM→SIGKILL when a clean {@link #forceStop} can't reach a wedged engine. Never
     * targets the calling process (in-process {@code EngineServer} tests share this JVM's pid).
     *
     * <p>Interrupt escalates immediately: a Ctrl-C during the 30s grace window means the
     * user wants out now, so the wedged engine gets SIGKILL right away instead of the pre-peel
     * behavior of waiting out the full deadline with the interrupt flag parked. The target is
     * already known-wedged — there is no clean-exit path worth 30 more seconds of a held terminal.
     */
    public static void hardKill(long pid) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (h.isAlive() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (h.isAlive()) h.destroyForcibly();
        });
    }

    /**
     * Read the engine pid from the socket's sibling {@code.pid} file (generation-scoped). {@code -1}
     * when missing or unreadable.
     */
    static long readPidForSocket(Path socket) {
        return readPidFile(EnginePaths.pidFor(socket));
    }

    /** First line of a pid file as a long, or {@code -1}. */
    static long readPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) return -1;
            String first =
                    Files.readString(pidFile).lines().findFirst().orElse("").trim();
            if (first.isEmpty()) return -1;
            return Long.parseLong(first);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Wait up to {@code timeout} for {@code pid} to exit; {@link #hardKill} if still alive. No-op when
     * the process is already gone, pid is non-positive, or pid is this JVM (in-process engine tests).
     */
    static void waitForDeathOrKill(long pid, Duration timeout) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!handle.get().isAlive()) return;
            sleepQuietly(20);
        }
        if (handle.get().isAlive()) hardKill(pid);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- build history ({@code jk history}) — thin RPC over the engine's journal ----------------

    /** Newest-first {@code history-entry} lines (flat JSONL), spawning the engine if none is running. */
    public static List<String> historyList(EnginePaths.Paths paths, int limit) throws IOException {
        return streamHistory(paths, ProtoSession.historyListRequest(limit));
    }

    /** One entry's detail: a {@code history-record} header line plus module/step/diag lines. */
    public static List<String> historyShow(EnginePaths.Paths paths, String id) throws IOException {
        return streamHistory(paths, ProtoSession.historyShowRequest(id));
    }

    /** Delete one entry; {@code true} if it existed. */
    public static boolean historyDelete(EnginePaths.Paths paths, String id) throws IOException {
        for (String line : streamHistory(paths, ProtoSession.historyDeleteRequest(id))) {
            if (EngineProtocol.HISTORY_DELETED.equals(EngineProtocol.typeOf(line))) {
                return Jsonl.bool(line, "deleted", false);
            }
        }
        return false;
    }

    /**
     * Cancel a live engine job by jid. Returns the {@code cancel-ack} line, or empty if the
     * engine is unreachable. Idempotent: already-finished jids yield {@code cancelled=false}.
     */
    public static Optional<String> cancel(EnginePaths.Paths paths, long jid) throws IOException {
        ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        return cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequest(jid), jid);
    }

    /**
     * Cancel every live job under {@code dir}. Used by bare {@code jk cancel} and Ctrl-C.
     */
    public static Optional<String> cancelForDir(EnginePaths.Paths paths, String dir) throws IOException {
        ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        Optional<String> ack = cancelOnce(EnginePaths.activeSocket(paths), ProtoLifecycle.cancelRequestForDir(dir), -1);
        if (ack.isPresent()) ActiveJobs.forgetAll();
        return ack;
    }

    /**
     * SIGINT pathsame {@code cancel-request} wire as {@link #cancel}/{@link #cancelForDir},
     * but <em>never</em> spawns or replaces an engine and never blocks long. Call this from the
     * Ctrl-C handler before {@code halt}; the hard exit is the backup if this is too late.
     *
     * <p>Cancels every tracked jid from this CLI process, then a dir-scoped cancel for {@code cwd}.
     * All failures are swallowed.
     */
    public static void cancelBestEffortForInterrupt(Path cwd) {
        try {
            // Overall deadline: each RPC self-limits at SOCKET_TIMEOUT_MILLIS, but N stale jids
            // against a wedged engine would still serialize to N×2s of dead air.
            long deadline = System.nanoTime() + 3 * SOCKET_TIMEOUT_MILLIS * 1_000_000L / 2;
            Path socket = EnginePaths.activeSocket(EnginePaths.current());
            for (long jid : ActiveJobs.snapshot()) {
                if (System.nanoTime() >= deadline) break;
                try {
                    cancelOnce(socket, ProtoLifecycle.cancelRequest(jid), jid);
                } catch (Exception ignored) {
                    // best-effort — halt follows
                }
            }
            if (cwd != null && System.nanoTime() < deadline) {
                try {
                    Optional<String> ack = cancelOnce(socket, ProtoLifecycle.cancelRequestForDir(cwd.toString()), -1);
                    if (ack.isPresent()) ActiveJobs.forgetAll();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        } catch (Throwable ignored) {
            // never throw into the SIGINT handler
        }
    }

    /**
     * One cancel-request RPC on an already-running engine. Does not {@link #ensureRunning}. Used by
     * the public cancel APIs and the interrupt best-effort path.
     *
     * @param forgetJid when ≥ 0, removed from {@link ActiveJobs} on a positive ack
     */
    private static Optional<String> cancelOnce(Path socket, String requestLine, long forgetJid) throws IOException {
        try (SocketChannel ch = connect(socket)) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            writer.write(requestLine);
            writer.write('\n');
            writer.flush();
            // Watchdog: SIGINT must not hang waiting for a wedged engine.
            Thread watchdog = new Thread(
                    () -> {
                        try {
                            Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                            ch.close();
                        } catch (InterruptedException | IOException ignored) {
                            // done
                        }
                    },
                    "jk-cancel-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (EngineProtocol.CANCEL_ACK.equals(EngineProtocol.typeOf(line))) {
                        if (forgetJid >= 0) ActiveJobs.forget(forgetJid);
                        return Optional.of(line);
                    }
                }
            } finally {
                watchdog.interrupt();
            }
        }
        return Optional.empty();
    }

    /**
     * Process-local set of jids this CLI session has started (from {@code job-start} wire events).
     * Ctrl-C cancels these as a best-effort supplement to dir-based cancel.
     */
    public static final class ActiveJobs {
        private static final ConcurrentHashMap.KeySetView<Long, Boolean> LIVE = ConcurrentHashMap.newKeySet();

        private ActiveJobs() {}

        public static void note(long jid) {
            if (jid > 0) LIVE.add(jid);
        }

        public static void forget(long jid) {
            LIVE.remove(jid);
        }

        public static void forgetAll() {
            LIVE.clear();
        }

        public static Set<Long> snapshot() {
            return Set.copyOf(LIVE);
        }
    }

    /**
     * Running aggregate rows ({@code metrics-entry} flat JSONL) for {@code dir}'s project tiers
     * plus the global tiers; {@code null} dir asks for every row. Spawns the engine if needed.
     */
    public static List<String> metrics(EnginePaths.Paths paths, String dir) throws IOException {
        return streamHistory(paths, ProtoSession.metricsRequest(dir));
    }

    /**
     * Run host hardware calibration on the engine. {@code engineColdStartMs} ≤0 omits the
     * client-measured cold-spawn component. Returns the {@code calibrate-ack} JSONL line, or empty
     * on protocol failure.
     */
    public static Optional<String> calibrate(EnginePaths.Paths paths, boolean force, long engineColdStartMs)
            throws IOException {
        // Network on by default (match Calibration.ensure); callers pass false under --offline.
        return calibrate(paths, force, engineColdStartMs, true);
    }

    public static Optional<String> calibrate(
            EnginePaths.Paths paths, boolean force, long engineColdStartMs, boolean allowNetwork) throws IOException {
        ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        try (SocketChannel ch = connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            writer.write(ProtoLifecycle.calibrateRequest(force, engineColdStartMs, allowNetwork));
            writer.write('\n');
            writer.flush();
            BufferedReader reader = protocolReader(ch);
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.CALIBRATE_ACK.equals(type)) return Optional.of(line);
                if (EngineProtocol.ERROR.equals(type)) return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Send a history/metrics request, collect the flat reply lines up to (not including) the terminal. */
    private static List<String> streamHistory(EnginePaths.Paths paths, String request) throws IOException {
        ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        List<String> out = new ArrayList<>();
        try (SocketChannel ch = connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            writer.write(request);
            writer.write('\n');
            writer.flush();
            BufferedReader reader = protocolReader(ch);
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.HISTORY_DONE.equals(type) || EngineProtocol.METRICS_DONE.equals(type)) break;
                out.add(line);
                if (EngineProtocol.HISTORY_DELETED.equals(type) || EngineProtocol.ERROR.equals(type)) break;
            }
        }
        return out;
    }

    /**
     * The one entry point real commands use: a live, version-matched engine is guaranteed to be
     * reachable at {@code paths.socket} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) engine transparently. Throws with a
     * message pointing at the engine's log file if it still can't be reached after a fresh spawn
     * per {@code docs/architecture.md}, the engine is load-bearing and this is not silently swallowed.
     */
    public static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion) throws IOException {
        return EngineSpawn.ensure(paths, clientVersion);
    }

    /**
     * Run a workspace build against the engine at {@code paths} instead of in-process — the engine
     * equivalent of the engine's {@code BuildService.buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/architecture.md} there is no in-process fallback.
     */
    public static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        return EngineBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Workspace-member {@code jk image} — workspace events, image terminal on the module. */
    public static WorkspaceResult runImageWorkspace(
            EnginePaths.Paths paths, EngineRequests.ImageRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.runImageWorkspace(paths, req, listener);
    }

    /** Workspace {@code jk compile} — workspace events, compile-only terminal on the selection. */
    public static WorkspaceResult runCompileWorkspace(
            EnginePaths.Paths paths, EngineRequests.CompileRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.runCompileWorkspace(paths, req, listener);
    }

    /**
     * Run a single project's test plan against the engine (Task 3) — see {@link
     * EngineBuildListenerAdapter#runTest} for the exact contract.
     */
    public static cc.jumpkick.run.BuildPlanResult runTest(
            EnginePaths.Paths paths,
            EngineRequests.TestRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runTest(paths, req, listenerFactory, testResultOut);
    }

    /**
     * Run a single (non-workspace) project's build against the engine — the engine equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * EngineBuildListenerAdapter#runSingleBuild} for the exact contract.
     */
    public static cc.jumpkick.run.BuildPlanResult runSingleBuild(
            EnginePaths.Paths paths,
            EngineRequests.SingleBuildRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /** One engine-hosted jk.toml edit (EDIT_REQUEST): returns changed; throws on error. */
    public static boolean edit(cc.jumpkick.engine.EnginePaths.Paths paths, Path file, String op, List<String> args)
            throws IOException {
        return EngineBuildListenerAdapter.edit(paths, file, op, args);
    }

    public static String editDetail(cc.jumpkick.engine.EnginePaths.Paths paths, Path file, String op, List<String> args)
            throws IOException {
        return EngineBuildListenerAdapter.editDetail(paths, file, op, args);
    }

    public static cc.jumpkick.engine.protocol.CacheInventoryAck cacheInventory(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            String query,
            Path cache,
            Path store,
            List<String> terms,
            List<String> coords,
            boolean dryRun)
            throws IOException {
        return EngineBuildListenerAdapter.cacheInventory(paths, query, cache, store, terms, coords, dryRun);
    }

    public static cc.jumpkick.engine.protocol.PluginInstallLocalAck pluginInstallLocal(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            Path dir,
            Path cache,
            Path installRoot,
            String modules,
            boolean dryRun,
            boolean ambientStore)
            throws IOException {
        return EngineBuildListenerAdapter.pluginInstallLocal(
                paths, dir, cache, installRoot, modules, dryRun, ambientStore);
    }

    /** Engine-hosted {@code jk new} / init scaffold. */
    public static cc.jumpkick.engine.protocol.NewProjectAck newProject(
            cc.jumpkick.engine.EnginePaths.Paths paths, EngineRequests.NewProjectRequest req) throws IOException {
        return EngineBuildListenerAdapter.newProject(paths, req);
    }

    /**
     * On-demand, engine-hosted freshen of a network-backed catalog — {@code "templates"} (before
     * {@code jk new}/{@code init}) or {@code "libraries"} (before {@code jk lock}/{@code update}).
     * Starts the engine if it isn't already running (these two commands have no bootstrap concern —
     * they never need to run before a JDK exists). {@code url}/{@code cacheFile} override the
     * default source/destination ({@code "libraries"} only; {@code null} for {@code "templates"}).
     * Best-effort: never throws — a stale/offline catalog is not this call's problem, the caller
     * resolves against whatever the local cache already holds.
     *
     * <p>{@code jk jdk install}/{@code update} must not use this — use {@link
     * #freshenCatalogIfRunning} instead, which never starts an engine.
     */
    public static void freshenCatalog(
            cc.jumpkick.engine.EnginePaths.Paths paths, String catalog, boolean offline, String url, Path cacheFile) {
        if (offline) return; // nothing to freshen without a network
        try {
            ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        } catch (IOException e) {
            return; // no engine to host the freshen — local resolution proceeds against the cache
        }
        EngineBuildListenerAdapter.freshenCatalog(
                paths, catalog, false, url, cacheFile == null ? null : cacheFile.toString(), false);
    }

    /**
     * As {@link #freshenCatalog} but always hits the network and returns the engine error (or
     * {@code null} on success). Used by {@code jk library update}.
     */
    public static String freshenCatalogNow(
            cc.jumpkick.engine.EnginePaths.Paths paths, String catalog, String url, Path cacheFile) throws IOException {
        ensureRunning(paths, cc.jumpkick.cli.Jk.VERSION);
        return EngineBuildListenerAdapter.freshenCatalogNow(
                paths, catalog, url, cacheFile == null ? null : cacheFile.toString());
    }

    /**
     * As {@link #freshenCatalog}, but for {@code "jdks"} from {@code jk jdk install}/{@code
     * update} specifically: it must work to bootstrap a bare machine that has no JDK at all yet
     * (possibly the one that will host the engine), so it never starts an engine — only an
     * already-reachable one is asked to freshen. Returns {@code true} when it delegated (an engine
     * answered); {@code false} means nothing happened here and the caller must fetch {@code
     * jdks.json} itself ({@code JdkCatalogClient}). Never throws.
     *
     * <p>Once a healthy engine is running, every client — this CLI path included — funnels JDK
     * installs through it the same way the web dashboard and MCP always do, so there is one place
     * that actually touches the JDK feed's network when the engine is available.
     */
    public static boolean freshenCatalogIfRunning(
            cc.jumpkick.engine.EnginePaths.Paths paths, String catalog, String url, Path cacheFile) {
        if (!reachable(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) return false;
        EngineBuildListenerAdapter.freshenCatalog(
                paths, catalog, false, url, cacheFile == null ? null : cacheFile.toString());
        return true;
    }

    /** Module DAG for {@code jk explain --graph}. */
    public static cc.jumpkick.engine.protocol.ModuleGraphAck moduleGraph(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, String format, String modules, String affectedSince)
            throws IOException {
        return EngineBuildListenerAdapter.moduleGraph(paths, dir, format, modules, affectedSince);
    }

    /** Layered library catalog (list / search / wizard picker). */
    public static cc.jumpkick.engine.protocol.CatalogReadAck catalogRead(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly)
            throws IOException {
        return EngineBuildListenerAdapter.catalogRead(
                paths, dir, cache, query, terms, offline, includeCached, bundledOnly);
    }

    /**
     * Project summary (PROJECT_INFO) — replaces client-side project-file peeks.
     * In-process twin under test/no-engine.
     */
    public static cc.jumpkick.engine.protocol.ProjectInfo projectInfo(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir) throws IOException {
        return projectInfo(paths, dir, null, null);
    }

    public static cc.jumpkick.engine.protocol.ProjectInfo projectInfo(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, String modules, String affectedSince)
            throws IOException {
        return EngineBuildListenerAdapter.projectInfo(paths, dir, modules, affectedSince);
    }

    /**
     * Thin-client deny check: the [deny] policy is user-authored jk.toml and therefore parses
     * engine-side only; one synchronous DENY_CHECK round trip returns the violations.
     */
    /** Thin-client IDE model: engine computes the workspace model, client generates the files. */
    public static cc.jumpkick.engine.protocol.IdeWireModel ideModel(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, Path cache, Path jdksDir) throws IOException {
        return EngineBuildListenerAdapter.ideModel(paths, dir, cache, jdksDir);
    }

    /** A plugin-declared command, worker-executed engine-side (found=false → normal help). */
    public static cc.jumpkick.engine.protocol.PluginCommandReport pluginCommand(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, Path cache, String command, List<String> args)
            throws IOException {
        return EngineBuildListenerAdapter.pluginCommand(paths, dir, cache, command, args);
    }

    /** Thin-client generator run: engine renders content, client guards/writes/prints. */
    public static cc.jumpkick.engine.protocol.GeneratedFiles generate(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, String kind) throws IOException {
        return EngineBuildListenerAdapter.generate(paths, dir, kind, Map.of());
    }

    /** As above with generator parameters (scaffold inputs etc.). */
    public static cc.jumpkick.engine.protocol.GeneratedFiles generate(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, String kind, Map<String, String> params)
            throws IOException {
        return EngineBuildListenerAdapter.generate(paths, dir, kind, params);
    }

    /** Thin-client tree render: engine walks the graph, client substitutes its Theme into the tags. */
    public static String treeRender(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            Path dir,
            int maxDepth,
            boolean flatten,
            boolean stack,
            List<String> scopes)
            throws IOException {
        return EngineBuildListenerAdapter.treeRender(paths, dir, maxDepth, flatten, stack, scopes);
    }

    /** Thin-client why lookup: lock matching + provenance paths, engine-side. */
    public static cc.jumpkick.engine.protocol.WhyReport why(
            cc.jumpkick.engine.EnginePaths.Paths paths, Path dir, String query) throws IOException {
        return EngineBuildListenerAdapter.why(paths, dir, query);
    }

    public static cc.jumpkick.engine.protocol.DenyReport denyCheck(cc.jumpkick.engine.EnginePaths.Paths paths, Path dir)
            throws IOException {
        return EngineBuildListenerAdapter.denyCheck(paths, dir);
    }

    /**
     * Execution plan: engine decides run/dev argv, install layout, or aot-cache layout; caller
     * executes.
     */
    public static cc.jumpkick.engine.protocol.ExecPlan execPlan(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName)
            throws IOException {
        return EngineBuildListenerAdapter.execPlan(paths, dir, cache, kind, mainOverride, binName, null, null);
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static cc.jumpkick.engine.protocol.ExecPlan execPlan(
            cc.jumpkick.engine.EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName,
            Path binDir,
            Path libDir)
            throws IOException {
        return EngineBuildListenerAdapter.execPlan(paths, dir, cache, kind, mainOverride, binName, binDir, libDir);
    }

    /**
     * Pre-flight a build's dirty forecast against the engine — {@code jk build}'s fully-cached
     * shortcut and dirty hint (see {@link EngineBuildListenerAdapter#forecast}).
     */
    public static cc.jumpkick.runtime.BuildForecast forecast(
            EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests) throws IOException {
        return EngineBuildListenerAdapter.forecast(paths, entryDir, cache, skipTests);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineBuildListenerAdapter#explain} for the exact contract. {@code etaOut} (may be
     * {@code null}) receives engine-computed estimates in millis, {@code 0} = unknown:
     * slot {@code [0]} the remaining-work ETA, and — when the array has a second slot — slot
     * {@code [1]} the full-rebuild ETA (the rebuild-effort denominator). Length-guarded, so a
     * one-slot caller still gets the plain ETA.
     */
    public static ExplainPlan explain(EnginePaths.Paths paths, EngineRequests.ExplainRequest req, long[] etaOut)
            throws IOException {
        return EngineBuildListenerAdapter.explain(paths, req, etaOut);
    }

    /**
     * Run {@code jk lock}'s workspace cascade against the engine — see {@link
     * EngineResolveAdapter#runLock} for the exact contract. {@code handler} is the command's
     * renderer; the returned outcome's {@code exitCode} is authoritative (computed engine-side).
     */
    public static EngineRequests.LockOutcome runLock(
            EnginePaths.Paths paths, EngineRequests.LockRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runLock(paths, req, handler);
    }

    /**
     * Run {@code jk update}'s full re-resolve cascade against the engine (rides {@code jk lock}'s
     * event vocabulary) — see {@link EngineResolveAdapter#runUpdate}.
     */
    public static EngineRequests.LockOutcome runUpdate(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runUpdate(paths, req, handler);
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine ({@code gitTarget == null} refreshes
     * every git dependency) — see {@link EngineResolveAdapter#runUpdateGitOnly}.
     */
    public static EngineRequests.LockOutcome runUpdateGitOnly(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, String gitTarget) throws IOException {
        return EngineResolveAdapter.runUpdateGitOnly(paths, req, gitTarget);
    }

    /**
     * Run {@code jk sync}'s single plan against the engine — see {@link
     * EngineResolveAdapter#runSync} for the exact contract (the {@code jk test} listener-factory
     * shape, plus fetched/up-to-date count holders for the summary line).
     */
    public static cc.jumpkick.run.BuildPlanResult runSync(
            EnginePaths.Paths paths,
            EngineRequests.SyncRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        return EngineResolveAdapter.runSync(paths, req, listenerFactory, fetchedOut, upToDateOut);
    }

    /**
     * Report declared dependencies with newer versions available against the engine ({@code jk
     * outdated}) — one synchronous request, one {@link cc.jumpkick.engine.protocol.OutdatedReport}
     * back. Read-only: the engine enumerates versions and writes nothing.
     */
    public static cc.jumpkick.engine.protocol.OutdatedReport runOutdated(
            EnginePaths.Paths paths, EngineRequests.OutdatedRequest req) throws IOException {
        return EngineResolveAdapter.runOutdated(paths, req);
    }

    public static cc.jumpkick.run.BuildPlanResult runAudit(
            EnginePaths.Paths paths,
            EngineRequests.AuditRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.FindingObserver findings)
            throws IOException {
        return EngineHosted.runAudit(paths, req, listenerFactory, findings);
    }

    public static EngineRequests.FormatOutcome runFormat(
            EnginePaths.Paths paths,
            EngineRequests.FormatRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.FileObserver files)
            throws IOException {
        return EngineHosted.runFormat(paths, req, listenerFactory, files);
    }

    public static EngineRequests.PublishOutcome runPublish(
            EnginePaths.Paths paths,
            EngineRequests.PublishRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runPublish(paths, req, listenerFactory);
    }

    public static cc.jumpkick.run.BuildPlanResult runImage(
            EnginePaths.Paths paths,
            EngineRequests.ImageRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            EngineRequests.ImageSummary[] summaryOut)
            throws IOException {
        return EngineHosted.runImage(paths, req, listenerFactory, summaryOut);
    }

    public static EngineRequests.ImportOutcome runImport(
            EnginePaths.Paths paths,
            EngineRequests.ImportRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.NoteObserver notes)
            throws IOException {
        return EngineHosted.runImport(paths, req, listenerFactory, notes);
    }

    public static cc.jumpkick.runtime.HostedEvents.Provision provision(
            EnginePaths.Paths paths, Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EngineHosted.provision(paths, cache, projectDir, toolsRoot, noDiscover, gradle);
    }

    public static cc.jumpkick.run.BuildPlanResult runCompile(
            EnginePaths.Paths paths,
            EngineRequests.CompileRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runCompile(paths, req, listenerFactory);
    }

    public static cc.jumpkick.run.BuildPlanResult runTrain(
            EnginePaths.Paths paths,
            EngineRequests.TrainRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runTrain(paths, req, listenerFactory);
    }

    public static WorkspaceResult runNative(
            EnginePaths.Paths paths, EngineRequests.NativeRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineHosted.runNative(paths, req, listener);
    }

    public static cc.jumpkick.run.BuildPlanResult runInstall(
            EnginePaths.Paths paths,
            EngineRequests.InstallRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineHosted.runInstall(paths, req, listenerFactory, testResultOut);
    }

    public static EngineRequests.GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            EngineRequests.GitFetchRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runGitFetch(paths, req, listenerFactory);
    }

    public static EngineRequests.ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            EngineRequests.ToolResolveRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runToolResolve(paths, req, listenerFactory);
    }

    public static EngineRequests.ScriptPrepareOutcome runScriptPrepare(
            EnginePaths.Paths paths,
            EngineRequests.ScriptPrepareRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EngineHosted.runScriptPrepare(paths, req, listenerFactory);
    }

    public static cc.jumpkick.run.BuildPlanResult runCacheMaintenance(
            EnginePaths.Paths paths,
            EngineRequests.CacheMaintRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            ObjIntConsumer<Boolean> onWait,
            EngineRequests.CacheMaintSummary[] summaryOut)
            throws IOException {
        return EngineHosted.runCacheMaintenance(paths, req, listenerFactory, onWait, summaryOut);
    }

    static BufferedReader protocolReader(SocketChannel ch) {
        return EngineWire.protocolReader(ch);
    }

    static SocketChannel connect(Path socket) throws IOException {
        return EngineWire.connect(socket);
    }

    static String exchange(SocketChannel ch, String line) throws IOException {
        return EngineWire.exchange(ch, line);
    }

    static Optional<EngineSpawn.EngineArtifact> resolveEngineArtifact(String envOverride, String version) {
        return EngineSpawn.resolveEngineArtifact(envOverride, version);
    }

    static Optional<EngineSpawn.EngineArtifact> resolveEngineArtifact(
            String envOverride, String version, cc.jumpkick.cache.VersionStore store) {
        return EngineSpawn.resolveEngineArtifact(envOverride, version, store);
    }

    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineSpawn.EngineJdk jdk) {
        return EngineSpawn.aotCachePath(paths, engineJar, jdk);
    }

    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineSpawn.EngineJdk jdk, String version) {
        return EngineSpawn.aotCachePath(paths, engineJar, jdk, version);
    }

    static EngineSpawn.AotMode chooseAotMode(EngineSpawn.EngineTarget t) {
        return EngineSpawn.chooseAotMode(t);
    }

    static boolean scanLogForAotError(Path log) {
        return EngineSpawn.scanLogForAotError(log);
    }
}
