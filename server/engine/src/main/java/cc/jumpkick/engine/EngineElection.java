// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.OwnerOnlyFiles;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The engine's identity on disk: the startup mutex, the incumbent probe, the generation claim, the
 * bound listener, the pid file and the endpoint pointer — and the reverse of all of it at exit.
 *
 * <p>Election is settled once, before the accept loop starts, and is never re-decided. Afterwards
 * these files are only <em>read</em>, to answer "does the endpoint still name me?". That is why
 * identity can be a separate object while the plan-slot claim cannot: no thread has to observe an
 * election fact and a drain fact in the same atomic breath, so no lock crosses this boundary.
 *
 * <p>The rules it encodes, in order:
 *
 * <ul>
 *   <li><b>Startup mutex</b> — one spawn at a time gets from probe through bind to endpoint write.
 *   <li><b>Same-version election</b> — a live engine of this version <em>and</em> build identity
 *       means this process is a redundant spawn-race participant and loses quietly.
 *   <li><b>Generation claim</b> — the first free generation lock, which also reclaims the files of
 *       a crashed prior owner.
 *   <li><b>Takeover</b> — the endpoint write is the atomic handover point; the predecessor is then
 *       asked to drain and is waited for, so its HTTP port is free before the successor binds it.
 *       A draining predecessor keeps answering on its own generation socket until it exits.
 * </ul>
 */
final class EngineElection {

    /** How long a handover probe waits for the predecessor's {@code bye} after yield. */
    private static final long HANDOVER_BYE_MS = 2_000;

    /** Highest generation number tried before giving up on finding a free one. */
    private static final int MAX_GENERATIONS = 10_000;

    /** A live engine's identity as answered on the wire; a draining one serves no new jobs. */
    record Incumbent(String version, String buildId, long pid, boolean draining) {}

    /**
     * A won election: the generation this process owns, its bound listener, the loopback-TCP shared
     * secret ({@code null} on the Unix-domain transport), and the socket of the engine being
     * displaced ({@code null} when nothing was live).
     */
    record Won(
            EnginePaths.Paths active,
            ServerSocketChannel listener,
            @Nullable String token,
            @Nullable Path displaced) {}

    private final EnginePaths.Paths paths;
    private final String version;
    private final String buildId;
    private final long pid;
    private final Consumer<String> log;

    private @Nullable FileChannel lockChannel;
    private @Nullable FileLock lock;
    private @Nullable FileLock genLock;
    private @Nullable FileChannel genLockChannel;

    /** The generation this engine bound (socket/lock/pid/token) — see EnginePaths.generation. */
    private EnginePaths.@Nullable Paths active;

    /** The file key of the socket (or TCP port file) this engine bound, to tell it from a successor's. */
    private @Nullable Object boundSocketKey;

    /** Test seam: runs in {@link #retire} after the generation lock is released. */
    static @Nullable Runnable afterGenerationReleased;

    EngineElection(EnginePaths.Paths paths, String version, String buildId, long pid, Consumer<String> log) {
        this.paths = paths;
        this.version = version;
        this.buildId = buildId == null ? "" : buildId;
        this.pid = pid;
        this.log = log != null ? log : s -> {};
    }

    /**
     * Claim the engine identity and bind. Returns {@code null} — having touched nothing but the
     * lock file — when another engine is mid-startup, or when one of this exact version and build
     * identity already serves; the caller is then a losing spawn-race participant and should treat
     * that as success-by-proxy, not an error.
     */
    @Nullable
    Won win() throws IOException {
        // The Unix socket is trusted on this directory's permissions alone: owner-only, always.
        OwnerOnlyFiles.directory(paths.dir());
        // Startup mutex: serializes concurrent spawns/takeovers through bind + endpoint write.
        lockChannel = OwnerOnlyFiles.channel(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            // Losing is success-by-proxy, but a silent exit-0 reads as a crash in the engine log —
            // an unreachable winner then blocks every spawn with nothing anywhere saying why.
            log.accept("jk engine: another engine is mid-startup (" + paths.lock() + " is held) — yielding");
            return null;
        }

        // Where clients currently connect — the engine this one displaces (drained later).
        // Captured BEFORE we bind, and null when nothing was live: once the compat pointer is
        // written the flat path names US, and a drain aimed there is a self-shutdown.
        Path previousActive = EnginePaths.activeSocket(paths);
        if (previousActive != null && !Files.exists(previousActive)) previousActive = null;

        // Same-version election: if a live engine of THIS version AND build identity already
        // serves, this instance is a redundant spawn-race participant — lose quietly. A different
        // version — or the same -SNAPSHOT version with a DIFFERENT buildId (a rebuilt dev
        // engine; stale incumbents once won these elections and served old code) — proceeds to
        // takeover. An empty buildId on either side means "no opinion": version rule only. A
        // draining incumbent still answers its socket but serves no new job, so it is succeeded
        // whatever its version.
        Incumbent incumbent = helloProbe(previousActive, version);
        if (incumbent != null
                && !incumbent.draining()
                && version.equals(incumbent.version())
                && (buildId.isEmpty() || incumbent.buildId().isEmpty() || buildId.equals(incumbent.buildId()))) {
            releaseStartupLock();
            log.accept("jk engine: an identical engine already serves (pid " + incumbent.pid() + ", version "
                    + incumbent.version() + ") — yielding");
            return null;
        }

        // Claim the first free generation. The winner's gen lock is held for the engine's whole
        // life; a crashed engine's stale gen files are reclaimed here by winning its lock. A won
        // lock is not proof the generation is free: an engine whose lock file was unlinked under it
        // (by an older jk's retire) still serves on the name, so a generation whose socket answers
        // is skipped, never reclaimed.
        for (int n = 1; n < MAX_GENERATIONS && active == null; n++) {
            EnginePaths.Paths cand = EnginePaths.generation(paths, n);
            FileChannel gc = OwnerOnlyFiles.channel(cand.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock gl;
            try {
                gl = gc.tryLock();
            } catch (OverlappingFileLockException e) {
                gl = null;
            }
            Incumbent squatter = gl != null ? helloProbe(cand.socket(), version) : null;
            if (gl != null && squatter == null) {
                active = cand;
                genLock = gl;
                genLockChannel = gc;
                log.accept("jk engine: claimed generation " + n + " (pid " + pid + ", lock inode "
                        + fileKey(cand.lock()) + ")");
            } else {
                if (squatter != null) {
                    log.accept("jk engine: skipped generation " + n + ": its lock was free but pid " + squatter.pid()
                            + " answers on " + cand.socket().getFileName());
                }
                if (gl != null) gl.release();
                gc.close();
            }
        }
        if (active == null) {
            releaseStartupLock();
            return null;
        }

        // Stale files from a crashed prior owner of this generation.
        Files.deleteIfExists(active.socket());
        Files.deleteIfExists(active.token());

        String token = null;
        ServerSocketChannel listener;
        if (EngineTransport.useLoopbackTcp()) {
            // Windows: no dependable Unix-domain-socket support — bind an ephemeral loopback TCP
            // port instead, and gate every connection on a shared secret (see EngineTransport),
            // since a TCP port (unlike a socket file) isn't filesystem-permission-gated by default.
            listener = ServerSocketChannel.open();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            token = EngineTransport.newToken();
            // This token gates every engine RPC — i.e. arbitrary code execution as the engine
            // owner. It must be owner-only, like the HTTP bearer token, not left to the ambient
            // umask on a shared machine.
            OwnerOnlyFiles.write(
                    Objects.requireNonNull(active.token().getParent(), "token dir"), active.token(), token);
            OwnerOnlyFiles.writeString(active.socket(), Integer.toString(port));
        } else {
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(active.socket()));
            // Defence in depth under the 0700 directory; bind itself follows the umask.
            OwnerOnlyFiles.setOwnerOnly(active.socket(), "rw-------");
        }
        boundSocketKey = fileKey(active.socket());
        // The pid file is the pid and nothing else: a script reads it whole as a process id.
        OwnerOnlyFiles.writeString(active.pid(), pid + "\n");

        // TAKEOVER: from this write on, every new connection resolves to this generation.
        EnginePaths.writeEndpoint(paths, active.socket());
        releaseStartupLock();
        return new Won(active, listener, token, previousActive);
    }

    /**
     * Tell a displaced predecessor to drain. Blocks until {@code bye} so its HTTP port is free
     * before this engine binds HTTP; the predecessor keeps its own generation socket until it exits.
     */
    void askPredecessorToYield(@Nullable Path previousActive) {
        if (previousActive == null || previousActive.equals(bound().socket())) return;
        if (!Files.exists(previousActive)) return;
        if (namesSelf(previousActive)) return; // a stale flat pointer we just re-claimed — never self-drain
        try (SocketChannel ch = openClient(previousActive)) {
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r = new BoundedLineReader(
                    new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8), ch, HANDOVER_BYE_MS);
            w.write(ProtoLifecycle.shutdown(false));
            w.write('\n');
            w.flush();
            String bye = r.readLine();
            int plans = bye != null ? Jsonl.intValue(bye, "plans", 0) : 0;
            log.accept("jk engine: asked displaced engine at "
                    + previousActive.getFileName()
                    + " to yield"
                    + (plans > 0 ? " (" + plans + " job(s) still running)" : ""));
        } catch (IOException e) {
            // Nothing live there (stale file) — the watchdog on the other side also covers us.
        }
    }

    /**
     * True when this process is no longer the named primary: the endpoint points at another
     * generation, the pid file names another process, or the socket path answers as another pid
     * (state-dir deleted and rebound onto this generation name).
     */
    boolean displacedBySuccessor() throws IOException {
        if (active == null) return false;
        Path ep = EnginePaths.endpoint(paths);
        String mine = active.socket().getFileName().toString();
        if (Files.isRegularFile(ep) && !mine.equals(Files.readString(ep).trim())) {
            return true;
        }
        long named = readPidFile(active.pid());
        if (named > 0 && named != pid) {
            return true;
        }
        if (named == pid) {
            return false; // pid file still ours; generation-name check above covered a gen N+1 takeover
        }
        // Pid file missing (state dir deleted). Hello the path: a rebound successor answers as another pid.
        Incumbent live = helloProbe(EnginePaths.activeSocket(paths), version);
        return live != null && live.pid() > 0 && live.pid() != pid;
    }

    /** Whether the endpoint pointer still names this generation's socket. */
    boolean endpointNamesThisEngine() {
        if (active == null) return false;
        try {
            Path ep = EnginePaths.endpoint(paths);
            if (!Files.isRegularFile(ep)) return false;
            return active.socket()
                    .getFileName()
                    .toString()
                    .equals(Files.readString(ep).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** True when no endpoint pointer exists at all — nobody names this engine, and nobody succeeded it. */
    boolean endpointMissing() {
        return !Files.exists(EnginePaths.endpoint(paths));
    }

    /**
     * Undo {@link #win}: drop the socket, token and pid file this engine still owns, un-point the
     * endpoint only if it still names US and the pid file says so, then release the generation and
     * startup locks.
     *
     * <p>The lock files themselves stay. Deleting one after its release lets a successor's lock land
     * on the unlinked file while a third engine locks a fresh one at the same path, and both then
     * own the generation — the second deletes the first's live socket as stale. A socket is removed
     * only while the pid file still names this engine: a successor that rebound the name keeps it.
     */
    void retire() {
        if (active != null) {
            boolean pidIsMine = readPidFile(active.pid()) == pid;
            // A freed inode is reused at once, so the file key alone cannot tell a successor's
            // socket from ours; the pid file it rewrote can. The key adds a check where it exists.
            boolean socketIsMine =
                    pidIsMine && (boundSocketKey == null || boundSocketKey.equals(fileKey(active.socket())));
            if (socketIsMine) {
                deleteQuietly(active.socket());
                deleteQuietly(active.token());
            }
            if (pidIsMine) deleteQuietly(active.pid());
            // The endpoint names a socket, and a successor may have bound the same name; only an
            // engine the pid file still names may un-point it.
            if (pidIsMine && endpointNamesThisEngine()) deleteQuietly(EnginePaths.endpoint(paths));
            try {
                if (genLock != null) genLock.release();
                if (genLockChannel != null) genLockChannel.close();
            } catch (IOException ignored) {
                // process exit releases it regardless
            }
            Runnable seam = afterGenerationReleased;
            if (seam != null) seam.run();
        }
        releaseStartupLock();
    }

    /** The file's identity (inode on Unix), or {@code null} when it is missing or unreadable. */
    private static @Nullable Object fileKey(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True when {@code candidate} resolves to THIS engine's own listener — the flat compat
     * pointer after we've re-claimed a crashed generation's name (Unix symlink → our gen socket;
     * TCP → a copy of our own port). Drain/probe traffic must never target it.
     */
    private boolean namesSelf(Path candidate) {
        try {
            if (EngineTransport.useLoopbackTcp()) {
                return Files.readString(candidate)
                        .trim()
                        .equals(Files.readString(bound().socket()).trim());
            }
            return candidate.toRealPath().equals(bound().socket().toRealPath());
        } catch (IOException e) {
            return false; // unreadable/vanished — the connect attempt sorts it out
        }
    }

    private void releaseStartupLock() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // best-effort; process exit releases it regardless
        }
        lock = null;
        lockChannel = null;
    }

    /** The generation this election claimed. Only callable after {@link #win} returned non-null. */
    private EnginePaths.Paths bound() {
        return Objects.requireNonNull(active, "election has not bound a generation");
    }

    /** The identity a live engine at {@code socket} answers with, or {@code null}. */
    static @Nullable Incumbent helloProbe(@Nullable Path socket, String probeVersion) {
        if (socket == null || !Files.exists(socket)) return null;
        try (SocketChannel ch = openClient(socket)) {
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader r = new BoundedLineReader(
                    new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8), ch, HANDOVER_BYE_MS);
            w.write(ProtoLifecycle.hello(probeVersion, "probe"));
            w.write('\n');
            w.flush();
            String ack = r.readLine();
            if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
            String v = Jsonl.str(ack, "version");
            if (v == null) return null;
            String id = Jsonl.str(ack, "buildId");
            return new Incumbent(
                    v, id == null ? "" : id, Jsonl.longValue(ack, "pid", -1), Jsonl.bool(ack, "draining", false));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Minimal client connect for engine→engine signalling (token-gated on the TCP transport).
     * Shared with {@link DrainReporter}, which talks to the successor over the same channel.
     */
    static SocketChannel openClient(Path socket) throws IOException {
        if (EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            Path token = EnginePaths.tokenFor(socket);
            BufferedWriter w =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            // The auth envelope, exactly as the CLI client sends it — authenticate accepts
            // nothing else (a raw token line here once broke takeover/election on TCP).
            w.write(ProtoLifecycle.auth(Files.readString(token).trim()));
            w.write('\n');
            w.flush();
            return ch;
        }
        return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    }

    private static long readPidFile(Path pidFile) {
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

    private static void deleteQuietly(@Nullable Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup — a leftover file is harmless (recreated/overwritten next start)
        }
    }
}
