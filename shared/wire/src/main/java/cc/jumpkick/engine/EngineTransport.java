// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Engine transport seam: Unix domain socket on macOS/Linux, loopback TCP + shared-secret token on
 * Windows. Request handling only sees a connected {@code SocketChannel}.
 */
public final class EngineTransport {

    private EngineTransport() {}

    /**
     * True on Windows — the one platform where the Unix-domain-socket path isn't used. Tests and
     * release smokes force either transport with {@code -Djk.engine.transport=tcp|unix} so the
     * TCP lane (auth handshake included) is exercisable off-Windows.
     */
    public static boolean useLoopbackTcp() {
        String forced = System.getProperty("jk.engine.transport", "");
        if ("tcp".equals(forced)) return true;
        if ("unix".equals(forced)) return false;
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** A fresh per-engine secret, URL-safe base64 — written to {@code paths.token()}, never logged. */
    public static String newToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
