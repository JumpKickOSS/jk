// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import cc.jumpkick.host.Os;
import java.security.SecureRandom;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Engine transport seam: Unix domain socket on macOS/Linux, loopback TCP + shared-secret token on
 * Windows. Request handling only sees a connected {@code SocketChannel}.
 */
public final class EngineTransport {

    private EngineTransport() {}

    /** System property spelling of the transport override. */
    public static final String TRANSPORT_PROPERTY = "jk.engine.transport";

    /** Environment spelling of the same override. */
    public static final String TRANSPORT_ENV = "JK_ENGINE_TRANSPORT";

    /**
     * True on Windows — the one platform where the Unix-domain-socket path isn't used. Tests and
     * release smokes force either transport with {@code tcp} / {@code unix} so the TCP lane (auth
     * handshake included) is exercisable off-Windows.
     *
     * <p>Property first, then environment. The environment spelling is the one that carries: this
     * decision is read by the client <em>and</em> by the engine it spawns, and a spawned engine
     * inherits the environment but not the spawner's system properties. A test tier that forces
     * a transport with {@code -D} alone moves the client to TCP and leaves the engine on a Unix
     * socket, which is a hang rather than a failure.
     */
    public static boolean useLoopbackTcp() {
        String forced = System.getProperty(TRANSPORT_PROPERTY);
        if (forced == null || forced.isBlank()) forced = System.getenv(TRANSPORT_ENV);
        return resolve(forced);
    }

    /** The decision for one already-read override value; {@code null}/blank/unrecognised = the platform default. */
    static boolean resolve(@Nullable String forced) {
        if (forced == null) return Os.isWindows();
        String v = forced.trim();
        if ("tcp".equalsIgnoreCase(v)) return true;
        if ("unix".equalsIgnoreCase(v)) return false;
        return Os.isWindows();
    }

    /** A fresh per-engine secret, URL-safe base64 — written to {@code paths.token()}, never logged. */
    public static String newToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
