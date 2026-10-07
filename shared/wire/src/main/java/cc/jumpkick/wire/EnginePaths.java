// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the on-disk identity of the engine for a given state directory: a short key
 * derived from the resolved, absolute state-dir path, and the socket/lock/pid/log files under it.
 *
 * <p>Keying off the state dir (rather than a fixed machine-wide name) means a different {@code
 * JK_HOME}/{@code JK_STATE_DIR} naturally gets its own engine, and every invocation that resolves the
 * same state dir naturally shares one — see {@code docs/architecture.md}.
 *
 * <p>The artifact store ({@code JK_STORE_DIR}) is part of the identity for the same reason. Without it,
 * an invocation asking for a different store silently reused an engine already bound to another one and
 * the setting did nothing — measured before closed this: {@code /proc/<engine>/environ} carried
 * no {@code JK_STORE_DIR} at all. The state dir alone is not enough, because two invocations can share
 * a state dir while disagreeing about where downloads belong.
 */
public final class EnginePaths {

    private EnginePaths() {}

    /** How many hex characters of the state-dir hash to use as the identity key. */
    private static final int KEY_LENGTH = 16;

    /**
     * The socket/lock/pid/log paths for one engine identity, all siblings under {@code engine/}.
     * {@code token} is only ever written/read on the {@link EngineTransport#useLoopbackTcp} path
     * (Windows) — on the Unix-domain-socket path it's simply never created. {@code http} holds the
     * embedded HTTP server's actual bound URL and {@code httpToken} its bearer token (owner-only
     * permissions); both exist only while an engine with an enabled {@code [http]} table is serving
     * (see {@code docs/http.md}).
     */
    public record Paths(
            String key, Path dir, Path socket, Path lock, Path pid, Path log, Path token, Path http, Path httpToken) {}

    /**
     * Resolve against the live {@link JkDirs} (honors {@code JK_HOME}/{@code JK_STATE_DIR}/{@code
     * JK_STORE_DIR}).
     */
    public static Paths current() {
        return resolve(JkDirs.state(), JkDirs.store());
    }

    /**
     * Resolve against an explicit state directory, pairing it with the ambient store — the seam tests
     * use.
     */
    public static Paths resolve(Path stateDir) {
        return resolve(stateDir, JkDirs.store());
    }

    /** Resolve against an explicit state directory and store root. */
    public static Paths resolve(Path stateDir, Path storeDir) {
        String key = keyFor(stateDir, storeDir);
        Path dir = stateDir.resolve("engine");
        return new Paths(
                key,
                dir,
                socketDir(dir).resolve(key + ".sock"),
                dir.resolve(key + ".lock"),
                dir.resolve(key + ".pid"),
                dir.resolve(key + ".log"),
                dir.resolve(key + ".token"),
                dir.resolve(key + ".http"),
                dir.resolve(key + ".http-token"));
    }

    /**
     * Every engine identity with an endpoint pointer under {@code stateDir}, newest file first.
     *
     * <p>Needed because the identity key is a hash: once the store became part of it, a machine
     * can hold several resident engines and {@link #current} names only the one this invocation would
     * talk to. Without a way to enumerate them, clearing the rest meant {@code pkill}.
     *
     * <p>Discovered from {@code <key>.endpoint} files rather than from any registry, so it stays true even
     * for an engine started by a jk that predates this method.
     */
    public static List<Paths> identitiesIn(Path stateDir) {
        Path dir = stateDir.resolve("engine");
        if (!Files.isDirectory(dir)) return List.of();
        List<Paths> out = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            List<Path> pointers = listing.filter(f -> f.getFileName().toString().endsWith(".endpoint"))
                    .sorted(Comparator.comparingLong(EnginePaths::lastModifiedOrZero)
                            .reversed())
                    .toList();
            for (Path pointer : pointers) {
                String file = pointer.getFileName().toString();
                String key = file.substring(0, file.length() - ".endpoint".length());
                out.add(forKey(key, stateDir));
            }
        } catch (IOException e) {
            return List.copyOf(out);
        }
        return List.copyOf(out);
    }

    private static long lastModifiedOrZero(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The paths for an already-known key — the inverse of hashing, for enumeration. */
    public static Paths forKey(String key, Path stateDir) {
        Path dir = stateDir.resolve("engine");
        return new Paths(
                key,
                dir,
                socketDir(dir).resolve(key + ".sock"),
                dir.resolve(key + ".lock"),
                dir.resolve(key + ".pid"),
                dir.resolve(key + ".log"),
                dir.resolve(key + ".token"),
                dir.resolve(key + ".http"),
                dir.resolve(key + ".http-token"));
    }

    // ---- generations + the endpoint pointer ------------------------------------------------

    /**
     * One-line file naming the current generation's socket. Clients resolve it before connect;
     * takeover is an atomic replace (portable; bound-socket rename is not).
     */
    public static Path endpoint(Paths paths) {
        return paths.dir().resolve(paths.key() + ".endpoint");
    }

    /**
     * Where the engine JVM writes its heap dump when it exits on {@code OutOfMemoryError}: the
     * engine directory itself. Given a directory, HotSpot names each dump {@code
     * java_pid<pid>.hprof}, so a second exit writes a fresh file where a fixed name would have been
     * refused ({@code O_EXCL}) and lost. The dumps sit beside the log, where the
     * next client, {@code jk engine status} and {@code jk doctor} look for the newest.
     */
    public static Path heapDumpDir(Paths paths) {
        return paths.dir();
    }

    /** Whether {@code file} is a heap dump the engine directory may hold. */
    public static boolean isHeapDump(Path file) {
        return file.getFileName().toString().endsWith(".hprof");
    }

    /** Generation {@code n}'s socket/lock/pid files ({@code <key>.gen<n>.sock} …). */
    public static Paths generation(Paths paths, int n) {
        String stem = paths.key() + ".gen" + n;
        Path dir = paths.dir();
        return new Paths(
                paths.key(),
                dir,
                socketDir(dir).resolve(stem + ".sock"),
                dir.resolve(stem + ".lock"),
                dir.resolve(stem + ".pid"),
                paths.log(),
                // Token is generation-scoped so overlapping engines never clobber each other's
                // secret; clients find it by tokenFor(socket) naming convention either way.
                dir.resolve(stem + ".token"),
                paths.http(),
                paths.httpToken());
    }

    /** Socket from the endpoint pointer when present, else the flat socket path. */
    public static Path activeSocket(Paths paths) {
        Path ep = endpoint(paths);
        try {
            String name = Files.readString(ep).trim();
            if (!name.isEmpty() && !name.contains("/") && !name.contains("\\")) {
                return reachableSocketDir(paths.dir()).resolve(name);
            }
        } catch (IOException ignored) {
            // No pointer → no engine. The flat path below is a never-bound placeholder (nothing
            // creates it): probes against it fail cleanly, which is exactly the "no engine
            // running" answer.
        }
        return paths.socket();
    }

    /** Atomically point the endpoint at {@code socket} (a sibling of the engine dir). */
    public static void writeEndpoint(Paths paths, Path socket) throws IOException {
        Path ep = endpoint(paths);
        // Durable: a torn endpoint pointer strands every client that reads it, and nothing
        // reconstructs it without a respawn.
        AtomicWrites.replaceDurably(ep, socket.getFileName().toString());
    }

    /**
     * The token-file sibling of a {@code .sock} path, derived by naming convention alone — so the
     * CLI-side client's {@code connect(Path)} (which only ever receives {@code paths.socket}, not
     * the full {@link Paths} record, across its several call sites) can find it without threading the
     * whole record through every method.
     */
    public static Path tokenFor(Path socket) {
        return siblingStem(socket, ".token");
    }

    /**
     * The pid-file sibling of a {@code .sock} path ({@code <stem>.pid}), same naming convention as
     * {@link #tokenFor} — used by the client to wait for process death after force-stop and to
     * displace a silent peer.
     */
    public static Path pidFor(Path socket) {
        return siblingStem(socket, ".pid");
    }

    private static Path siblingStem(Path socket, String suffix) {
        String name = socket.getFileName().toString();
        String base = name.endsWith(".sock") ? name.substring(0, name.length() - ".sock".length()) : name;
        return socket.resolveSibling(base + suffix);
    }

    // ---- the socket's directory -----------------------------------------------------------

    /**
     * The longest socket path the OS binds, in bytes: {@code sun_path} holds 108 bytes on Linux and
     * 104 on macOS, the terminating NUL included.
     */
    static int socketPathLimit() {
        return Os.isDarwin() ? 103 : 107;
    }

    /** The longest socket name an engine binds: {@code <key>.gen<n>.sock}, with room for a six-digit generation. */
    private static final String LONGEST_NAME = "0".repeat(KEY_LENGTH) + ".gen999999.sock";

    /**
     * Where {@code engineDir}'s sockets are bound and connected: {@code engineDir} itself when its
     * longest socket path fits the OS limit, else a short owner-only link to it under the system
     * temp root ({@link #shortLinkFor}). Every other engine file stays in {@code engineDir}; through
     * the link a socket's pid and token siblings are the same files.
     */
    public static Path socketDir(Path engineDir) {
        if (EngineTransport.useLoopbackTcp() || fits(engineDir.toAbsolutePath().normalize(), socketPathLimit())) {
            return engineDir;
        }
        return shortLinkFor(engineDir);
    }

    /** Whether the longest socket under {@code dir} fits {@code limit} bytes. */
    static boolean fits(Path dir, int limit) {
        return dir.resolve(LONGEST_NAME).toString().getBytes(StandardCharsets.UTF_8).length <= limit;
    }

    /**
     * {@code /tmp/jk-<user>/<hash>}: a link named by a hash of {@code engineDir}'s absolute path, so
     * two state directories never share one and the same directory always gets the same link.
     */
    static Path shortLinkFor(Path engineDir) {
        return linkRoot()
                .resolve(
                        Hashing.sha256Hex(engineDir.toAbsolutePath().normalize().toString())
                                .substring(0, 12));
    }

    private static Path linkRoot() {
        String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
        if (user.length() > 32) user = user.substring(0, 32);
        return Path.of("/tmp", "jk-" + user);
    }

    /**
     * {@link #socketDir}, with the short link created or repaired when one is in use, so a socket
     * bound or connected through it reaches {@code engineDir}. A link root that is not this user's
     * directory with mode {@code 0700} is not used: a socket path through it would let another
     * account stand in for the engine. Then, or on any I/O failure, the link stays absent and the
     * bind or connect through it fails naming the path.
     */
    public static Path reachableSocketDir(Path engineDir) {
        Path dir = socketDir(engineDir);
        if (dir.equals(engineDir)) return dir;
        try {
            ensureLink(dir, engineDir);
        } catch (IOException ignored) {
            // The bind or connect through the missing link reports the path.
        }
        return dir;
    }

    static void ensureLink(Path link, Path engineDir) throws IOException {
        Path target = engineDir.toAbsolutePath().normalize();
        Files.createDirectories(target);
        Path root = Objects.requireNonNull(link.getParent(), "link root");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                OwnerOnlyFiles.createDirectory(root);
            } catch (FileAlreadyExistsException raced) {
                // Another jk made it first; it is checked below like any existing root.
            }
        }
        if (!ownedPrivateDirectory(root, target)) {
            throw new IOException(root + " is not a directory of this user's with mode 0700");
        }
        if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).equals(target)) return;
        Path staging =
                root.resolve(link.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.deleteIfExists(staging);
        Files.createSymbolicLink(staging, target);
        try {
            Files.move(staging, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /** A real directory (not a link), owned by whoever owns {@code mine}, open to its owner alone. */
    private static boolean ownedPrivateDirectory(Path dir, Path mine) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(mine))) return false;
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
        return perms.equals(PosixFilePermissions.fromString("rwx------"));
    }

    /** A short, stable hash of the resolved absolute state-dir path. */
    static String keyFor(Path stateDir) {
        return keyFor(stateDir, JkDirs.store());
    }

    static String keyFor(Path stateDir, Path storeDir) {
        String hex = Hashing.sha256Hex(stateDir.toAbsolutePath().normalize().toString()
                + "\u0000"
                + storeDir.toAbsolutePath().normalize());
        return hex.substring(0, KEY_LENGTH);
    }
}
