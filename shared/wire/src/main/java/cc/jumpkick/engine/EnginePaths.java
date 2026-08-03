// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;

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
                dir.resolve(key + ".sock"),
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
    public static java.util.List<Paths> identitiesIn(Path stateDir) {
        Path dir = stateDir.resolve("engine");
        if (!java.nio.file.Files.isDirectory(dir)) return java.util.List.of();
        java.util.List<Paths> out = new java.util.ArrayList<>();
        try (var listing = java.nio.file.Files.list(dir)) {
            java.util.List<Path> pointers = listing.filter(
                            f -> f.getFileName().toString().endsWith(".endpoint"))
                    .sorted(java.util.Comparator.comparingLong(EnginePaths::lastModifiedOrZero)
                            .reversed())
                    .toList();
            for (Path pointer : pointers) {
                String file = pointer.getFileName().toString();
                String key = file.substring(0, file.length() - ".endpoint".length());
                out.add(forKey(key, stateDir));
            }
        } catch (java.io.IOException e) {
            return java.util.List.copyOf(out);
        }
        return java.util.List.copyOf(out);
    }

    private static long lastModifiedOrZero(Path p) {
        try {
            return java.nio.file.Files.getLastModifiedTime(p).toMillis();
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    /** The paths for an already-known key — the inverse of hashing, for enumeration. */
    private static Paths forKey(String key, Path stateDir) {
        Path dir = stateDir.resolve("engine");
        return new Paths(
                key,
                dir,
                dir.resolve(key + ".sock"),
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

    /** Generation {@code n}'s socket/lock/pid files ({@code <key>.gen<n>.sock} …). */
    public static Paths generation(Paths paths, int n) {
        String stem = paths.key() + ".gen" + n;
        Path dir = paths.dir();
        return new Paths(
                paths.key(),
                dir,
                dir.resolve(stem + ".sock"),
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
            String name = java.nio.file.Files.readString(ep).trim();
            if (!name.isEmpty() && !name.contains("/") && !name.contains("\\")) {
                return paths.dir().resolve(name);
            }
        } catch (java.io.IOException ignored) {
            // No pointer → no engine. The flat path below is a never-bound placeholder (nothing
            // creates it since the legacy compat pointer was retired): probes against it fail
            // cleanly, which is exactly the "no engine running" answer.
        }
        return paths.socket();
    }

    /** Atomically point the endpoint at {@code socket} (a sibling of the engine dir). */
    public static void writeEndpoint(Paths paths, Path socket) throws java.io.IOException {
        Path ep = endpoint(paths);
        AtomicWrites.replace(ep, socket.getFileName().toString());
    }

    /**
     * The token-file sibling of a {@code.sock} path, derived by naming convention alone — so the
     * CLI-side client's {@code connect(Path)} (which only ever receives {@code paths.socket}, not
     * the full {@link Paths} record, across its several call sites) can find it without threading the
     * whole record through every method.
     */
    public static Path tokenFor(Path socket) {
        return siblingStem(socket, ".token");
    }

    /**
     * The pid-file sibling of a {@code.sock} path ({@code <stem>.pid}), same naming convention as
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
