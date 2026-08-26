// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.OwnerOnlyFiles;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

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
 *       asked to yield and is waited for, so its listeners are free before the successor binds
 *       HTTP.
 * </ul>
 */
final class EngineElection {

    /** How long a handover probe waits for the predecessor's {@code bye} after yield. */
    private static final long HANDOVER_BYE_MS = 2_000;

    /** Highest generation number tried before giving up on finding a free one. */
    private static final int MAX_GENERATIONS = 10_000;

    /** A live engine's identity as answered on the wire. */
    record Incumbent(String version, String buildId, long pid) {}

    /**
     * A won election: the generation this process owns, its bound listener, the loopback-TCP shared
     * secret ({@code null} on the Unix-domain transport), and the socket of the engine being
     * displaced ({@code null} when nothing was live).
     */
    record Won(EnginePaths.Paths active, ServerSocketChannel listener, String token, Path displaced) {}

    private final EnginePaths.Paths paths;
    private final String version;
    private final String buildId;
    private final long pid;
    private final long startedAtMillis;
    private final Consumer<String> log;

    private FileChannel lockChannel;
    private FileLock lock;
    private FileLock genLock;
    private FileChannel genLockChannel;

    /** The generation this engine bound (socket/lock/pid/token) — see EnginePaths.generation. */
    private EnginePaths.Paths active;

    EngineElection(
            EnginePaths.Paths paths,
            String version,
            String buildId,
            long pid,
            long startedAtMillis,
            Consumer<String> log) {
        this.paths = paths;
        this.version = version;
        this.buildId = buildId == null ? "" : buildId;
        this.pid = pid;
        this.startedAtMillis = startedAtMillis;
        this.log = log != null ? log : s -> {};
    }

    /**
     * Claim the engine identity and bind. Returns {@code null} — having touched nothing but the
     * lock file — when another engine is mid-startup, or when one of this exact version and build
     * identity already serves; the caller is then a losing spawn-race participant and should treat
     * that as success-by-proxy, not an error.
     */
    Won win() throws IOException {
        Files.createDirectories(paths.dir());
        // Startup mutex: serializes concurrent spawns/takeovers through bind + endpoint write.
        lockChannel = FileChannel.open(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return null; // another engine is mid-startup — it wins this race
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
        // takeover. An empty buildId on either side means "no opinion": version rule only.
        Incumbent incumbent = helloProbe(previousActive, version);
        if (incumbent != null
                && version.equals(incumbent.version())
                && (buildId.isEmpty() || incumbent.buildId().isEmpty() || buildId.equals(incumbent.buildId()))) {
            releaseStartupLock();
            return null;
        }

        // Claim the first free generation. The winner's gen lock is held for the engine's whole
        // life; a crashed engine's stale gen files are reclaimed here by winning its lock.
        for (int n = 1; n < MAX_GENERATIONS && active == null; n++) {
            EnginePaths.Paths cand = EnginePaths.generation(paths, n);
            FileChannel gc = FileChannel.open(cand.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock gl;
            try {
                gl = gc.tryLock();
            } catch (OverlappingFileLockException e) {
                gl = null;
            }
            if (gl != null) {
                active = cand;
                genLock = gl;
                genLockChannel = gc;
            } else {
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
            OwnerOnlyFiles.write(active.token().getParent(), active.token(), token);
            Files.writeString(active.socket(), Integer.toString(port));
        } else {
            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(active.socket()));
        }
        Files.writeString(active.pid(), pid + "\n" + startedAtMillis + "\n", StandardCharsets.UTF_8);

        // TAKEOVER: from this write on, every new connection resolves to this generation.
        EnginePaths.writeEndpoint(paths, active.socket());
        releaseStartupLock();
        return new Won(active, listener, token, previousActive);
    }

    /**
     * Tell a displaced predecessor to yield its listeners and drain. Blocks until {@code bye} so
     * HTTP / the old UDS are free before this engine binds HTTP.
     */
    void askPredecessorToYield(Path previousActive) {
        if (previousActive == null || previousActive.equals(active.socket())) return;
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
     * Undo {@link #win}: drop this generation's files, release the generation and startup locks,
     * and un-point the endpoint only if it still names US — a takeover successor owns it now and
     * must not be un-pointed by the lame duck's exit.
     */
    void retire() {
        if (active != null) {
            deleteQuietly(active.socket());
            deleteQuietly(active.token());
            deleteQuietly(active.pid());
            if (endpointNamesThisEngine()) deleteQuietly(EnginePaths.endpoint(paths));
            try {
                if (genLock != null) genLock.release();
                if (genLockChannel != null) genLockChannel.close();
            } catch (IOException ignored) {
                // process exit releases it regardless
            }
            deleteQuietly(active.lock());
        }
        releaseStartupLock();
        deleteQuietly(paths.lock()); // the transient startup mutex file
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
                        .equals(Files.readString(active.socket()).trim());
            }
            return candidate.toRealPath().equals(active.socket().toRealPath());
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

    /** The identity a live engine at {@code socket} answers with, or {@code null}. */
    static Incumbent helloProbe(Path socket, String probeVersion) {
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
            return new Incumbent(v, id == null ? "" : id, Jsonl.longValue(ack, "pid", -1));
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

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup — a leftover file is harmless (recreated/overwritten next start)
        }
    }
}
