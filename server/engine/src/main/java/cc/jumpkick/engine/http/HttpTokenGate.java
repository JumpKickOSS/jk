// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.wire.EngineTransport;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The owner-only bearer token and the rules that consult it. Every {@code /api/*} exchange needs it
 * — loopback is not a free pass — and so does every MCP exchange, even on loopback: MCP is
 * agent-facing, the same CSRF posture as {@code POST /api/build}. {@code ?access_token=} is accepted
 * only for the {@code GET} shapes an {@code EventSource} can send, because it cannot set headers;
 * every other shape presents the {@code Bearer} header so tokens stay out of shell history and proxy
 * logs. Static assets are never gated: the dashboard shell has no secrets and must be able to show
 * the authorization dialog. A coverage report's path carries a {@linkplain #reportToken report token}
 * instead: one run's report, read only, for {@link #REPORT_TOKEN_LIFETIME}.
 */
final class HttpTokenGate {

    /** How long a report token opens its run's report; an open report tab works a working day. */
    static final Duration REPORT_TOKEN_LIFETIME = Duration.ofHours(8);

    private final Path tokenFile;
    private byte @Nullable [] token;

    /** @param tokenFile where the minted token persists, owner-only, so the CLI can hand out a tokenized URL */
    HttpTokenGate(Path tokenFile) {
        this.tokenFile = tokenFile;
    }

    /**
     * Load the owner-only bearer token from disk, or mint one. Stable across restarts so open
     * dashboard tabs keep working; rotate only via {@code jk engine rotate-token}. The file is
     * deliberately not deleted on shutdown. A token adopted from a file another user can read is
     * tightened to owner-only first.
     */
    void loadOrMint() throws IOException {
        String existing = readPersistedToken();
        if (existing != null) {
            OwnerOnlyFiles.setOwnerOnly(tokenFile, "rw-------");
            token = existing.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String minted = EngineTransport.newToken();
        token = minted.getBytes(StandardCharsets.UTF_8);
        Path dir = tokenFile.toAbsolutePath().getParent();
        OwnerOnlyFiles.write(dir == null ? tokenFile : dir, tokenFile, minted);
    }

    /** The bearer token in force, for links the engine itself hands out; null before {@link #loadOrMint}. */
    @Nullable
    String tokenText() {
        byte[] t = token;
        return t == null ? null : new String(t, StandardCharsets.UTF_8);
    }

    /** The persisted token if the file exists and holds a non-blank value, else {@code null}. */
    private @Nullable String readPersistedToken() {
        try {
            if (!Files.isRegularFile(tokenFile)) return null;
            String value = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null; // unreadable — mint a fresh one rather than fail to serve
        }
    }

    /**
     * The {@code /api/*} rule: a valid {@code Bearer} header, or {@code ?access_token=} on a read of
     * {@code /api/events}. A bare browser open without {@code #t=} or a stored token must not paint
     * live activity (fail-closed).
     */
    boolean authorizesApi(HttpExchange exchange) {
        return authorizesApi(exchange, exchange.getRequestURI().getPath());
    }

    /**
     * {@link #authorizesApi} for {@code path}, the request's path inside a front-door prefix: the
     * browser presents this engine's token, whatever engine the request is for.
     */
    boolean authorizesApi(HttpExchange exchange, String path) {
        if (tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))) return true;
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        return read
                && path.equals("/api/events")
                && tokenValid(
                        HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
    }

    /**
     * The MCP rule: a valid {@code Bearer} header, or {@code ?access_token=} on a {@code GET} that
     * accepts {@code text/event-stream} — the SSE GET is the one shape that cannot carry a header.
     */
    boolean authorizesMcp(HttpExchange exchange) {
        boolean sseQueryToken = exchange.getRequestMethod().equals("GET")
                && HttpAdmission.acceptsEventStream(exchange)
                && tokenValid(
                        HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
        return tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization"))) || sseQueryToken;
    }

    /** The 401 both rules answer with. */
    void challenge(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        HttpResponses.sendText(exchange, 401, "missing or invalid bearer token\n");
    }

    /** The credentials of a {@code Bearer} header; the scheme name is case-insensitive (RFC 9110 §11.1). */
    private static @Nullable String bearerToken(@Nullable String authorization) {
        String scheme = "Bearer ";
        if (authorization == null || !authorization.regionMatches(true, 0, scheme, 0, scheme.length())) return null;
        return authorization.substring(scheme.length()).trim();
    }

    /** Constant-time equality with the minted token; a path segment or query value presents the same way. */
    boolean tokenValid(@Nullable String presented) {
        if (presented == null || presented.isEmpty()) return false;
        // Constant-time, immune to length/prefix probing.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    /**
     * A token that opens run {@code runId}'s coverage report, and nothing else, until {@code
     * expiresAtSecond} (epoch seconds): {@code <expiry, base 36>.<MAC, base64url>}, the MAC an
     * HMAC-SHA256 of the run and the expiry keyed by the bearer token, so nothing is stored and
     * rotating the bearer token voids every one. Null before {@link #loadOrMint}.
     */
    @Nullable
    String reportToken(String runId, long expiresAtSecond) {
        byte[] key = token;
        if (key == null) return null;
        String expiry = Long.toString(expiresAtSecond, 36);
        return expiry + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(reportMac(key, runId, expiry));
    }

    /** Whether {@code presented} is a {@link #reportToken} for {@code runId} that has not expired at {@code nowMillis}. */
    boolean reportTokenValid(@Nullable String presented, String runId, long nowMillis) {
        byte[] key = token;
        if (key == null || presented == null) return false;
        int dot = presented.indexOf('.');
        if (dot <= 0) return false;
        String expiry = presented.substring(0, dot);
        byte[] mac;
        try {
            if (Long.parseLong(expiry, 36) <= nowMillis / 1000) return false;
            mac = Base64.getUrlDecoder().decode(presented.substring(dot + 1));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(mac, reportMac(key, runId, expiry));
    }

    private static byte[] reportMac(byte[] key, String runId, String expiry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("jk coverage report\n" + runId + "\n" + expiry).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
