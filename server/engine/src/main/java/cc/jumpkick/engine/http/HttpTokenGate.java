// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.wire.EngineTransport;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;

/**
 * The owner-only bearer token and the rules that consult it. Every {@code /api/*} exchange needs it
 * — loopback is not a free pass — and so does every MCP exchange, even on loopback: MCP is
 * agent-facing, the same CSRF posture as {@code POST /api/build}. {@code ?access_token=} is accepted
 * only for the {@code GET} shapes an {@code EventSource} can send, because it cannot set headers;
 * every other shape presents the {@code Bearer} header so tokens stay out of shell history and proxy
 * logs. Static assets are never gated: the dashboard shell has no secrets and must be able to show
 * the authorization dialog.
 */
final class HttpTokenGate {

    private final Path tokenFile;
    private byte[] token;

    /** @param tokenFile where the minted token persists, owner-only, so the CLI can hand out a tokenized URL */
    HttpTokenGate(Path tokenFile) {
        this.tokenFile = tokenFile;
    }

    /**
     * Load the owner-only bearer token from disk, or mint one. Stable across restarts so open
     * dashboard tabs keep working; rotate only via {@code jk engine rotate-token}. The file is
     * deliberately not deleted on shutdown.
     */
    void loadOrMint() throws IOException {
        String existing = readPersistedToken();
        if (existing != null) {
            token = existing.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String minted = EngineTransport.newToken();
        token = minted.getBytes(StandardCharsets.UTF_8);
        Files.deleteIfExists(tokenFile);
        try {
            Files.createFile(
                    tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tokenFile); // non-POSIX filesystem (Windows): default ACLs are per-user
        }
        Files.writeString(tokenFile, minted);
    }

    /** The persisted token if the file exists and holds a non-blank value, else {@code null}. */
    private String readPersistedToken() {
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
        if (tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))) return true;
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        return read
                && exchange.getRequestURI().getPath().equals("/api/events")
                && tokenValid(
                        HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
    }

    /**
     * The MCP rule: a valid {@code Bearer} header, or {@code ?access_token=} on a {@code GET} that
     * accepts {@code text/event-stream} — the SSE GET is the one shape that cannot carry a header.
     */
    boolean authorizesMcp(HttpExchange exchange) {
        boolean sseQueryToken = exchange.getRequestMethod().equals("GET")
                && HttpEngineServer.acceptsEventStream(exchange)
                && tokenValid(
                        HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "access_token"));
        return tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization"))) || sseQueryToken;
    }

    /** The 401 both rules answer with. */
    void challenge(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        HttpResponses.sendText(exchange, 401, "missing or invalid bearer token\n");
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return authorization.substring("Bearer ".length()).trim();
    }

    private boolean tokenValid(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        // Constant-time, immune to length/prefix probing.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }
}
