// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.host.time.Clock;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Connect-level faults — the connection refused, the host unknown or unroutable, the connect timed
 * out — and the addresses this process has found answering nothing.
 *
 * <p>A fault of this kind says nothing listens at an address, which is a fact about the address
 * rather than about one request. Once a whole retry ladder against an address ends in one, the
 * address is remembered for {@link #TTL} and every later request to it — from any client, any
 * repository object, any thread still walking its own ladder — is refused before it dials, with
 * the remembered fault as its cause. A materialize leg that fetches hundreds of pinned artifacts
 * from a lock naming a dead repository therefore pays one ladder, not one per artifact.
 *
 * <p>The key is the authority ({@code host:port}) the request dials — the proxy's when the client
 * routes it through one, else the URL's own — so a stub on another port of the same host is
 * unaffected, a settings.xml mirror routed to another address is asked on its own account, and a
 * host a direct request found dead is still asked through a proxy that can reach it.
 */
public final class ConnectFaults {

    /** How long an address that answered nothing is refused without a request. */
    static final Duration TTL = Duration.ofSeconds(60);

    private record Refusal(String fault, long expiresAtNanos) {}

    /** Authority → the fault it met and when the memory of it lapses. */
    private static final ConcurrentHashMap<String, Refusal> REFUSING = new ConcurrentHashMap<>();

    private ConnectFaults() {}

    /**
     * The connect-level fault in {@code failure}'s cause chain as one line, or null for any other
     * failure. A reset or a 5xx is one request the remote dropped; these say nothing answers at the
     * address. The JDK's HTTP client reports a refused connect as a {@link ConnectException} carrying
     * no message, and a peer that accepted and dropped the connection mid-handshake under the same
     * class with a message that says reset — only the latter is one request's failure.
     */
    public static @Nullable String describe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof Remembered remembered) return remembered.fault;
            String detail = t.getMessage();
            boolean reset = detail != null && detail.toLowerCase(Locale.ROOT).contains("reset");
            if (t instanceof ConnectException && !reset) {
                return detail == null || detail.isBlank()
                        ? "ConnectException: the connection was not accepted"
                        : "ConnectException: " + detail;
            }
            if (t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof UnresolvedAddressException) {
                return t.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
            }
        }
        return null;
    }

    /** Remember that the address {@code authority} met {@code fault} once a whole ladder against it failed. */
    static void noteRefusing(String authority, String fault) {
        REFUSING.put(authority, new Refusal(fault, Clock.SYSTEM.nanos() + TTL.toNanos()));
    }

    /** The fault the address {@code authority} met within {@link #TTL}, or null when it may be dialled. */
    static @Nullable String refusing(String authority) {
        Refusal refusal = REFUSING.get(authority);
        if (refusal == null) return null;
        if (Clock.SYSTEM.nanos() - refusal.expiresAtNanos() >= 0) {
            REFUSING.remove(authority, refusal);
            return null;
        }
        return refusal.fault();
    }

    /** As {@link #refusing(String)} for a request that dials {@code uri}'s own address. */
    static @Nullable String refusing(URI uri) {
        return refusing(authority(uri));
    }

    /** Forget every refusing address (a forced session, tests). */
    public static void forget() {
        REFUSING.clear();
    }

    /** {@code host:port} as the request opens it; the scheme's default port when the URL names none. */
    static String authority(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return host + ":" + port;
    }

    /**
     * The cause of a request refused before it dialled: the fault its address met a moment ago,
     * {@linkplain #describe read back} as that fault rather than as a new one.
     */
    public static final class Remembered extends ConnectException {
        private final String fault;

        Remembered(String authority, String fault) {
            super(authority + " answered nothing a moment ago (" + fault + ")");
            this.fault = fault;
        }
    }
}
