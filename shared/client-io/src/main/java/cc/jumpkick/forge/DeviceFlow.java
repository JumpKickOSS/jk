// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628) — the "copy this code into the browser" login
 * that {@code gh auth login} popularized. Works against any forge whose endpoints follow the RFC
 * (GitHub, GitLab, Gitea/Forgejo); Bitbucket has no device grant, so {@link
 * ForgeKind#deviceCodeUri} refuses to produce endpoints for it.
 *
 * <p>This class is the provider-neutral <i>mechanism</i>: it takes the two resolved endpoints and
 * drives the request → poll loop. Endpoint resolution is {@link ForgeKind}'s job; use {@link
 * #forHost} to wire the two together.
 */
@RequiredArgsConstructor
public final class DeviceFlow {

    /** Injectable sleep so tests don't wait real seconds between polls. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepSeconds(int seconds) throws InterruptedException;
    }

    private static final Sleeper REAL_SLEEP =
            s -> Thread.sleep(Duration.ofSeconds(s).toMillis());

    private final Http http;
    private final URI deviceCodeUri;
    private final URI tokenUri;
    private final String providerName;
    private final String clientId;
    private final String scope;
    private final Sleeper sleeper;

    public DeviceFlow(Http http, URI deviceCodeUri, URI tokenUri, String providerName, String clientId, String scope) {
        this(http, deviceCodeUri, tokenUri, providerName, clientId, scope, REAL_SLEEP);
    }

    /** Wire a flow for a concrete provider + host, sourcing endpoints from {@link ForgeKind}. */
    public static DeviceFlow forHost(Http http, ForgeKind kind, String host, String clientId, String scope) {
        if (!kind.supportsDeviceFlow()) {
            throw new AuthException(kind.displayName() + " does not support the device flow — use a token instead.");
        }
        return new DeviceFlow(http, kind.deviceCodeUri(host), kind.tokenUri(host), kind.displayName(), clientId, scope);
    }

    /**
     * Run the flow to completion and return the access token. {@code prompt} is invoked once with the
     * {@link DeviceCode} so the caller can display the user code / verification URL and optionally
     * open a browser; the code and URL must always be shown so headless sessions still work.
     */
    public String run(Consumer<DeviceCode> prompt) {
        DeviceCode dc = requestCode();
        prompt.accept(dc);
        return poll(dc);
    }

    private DeviceCode requestCode() {
        Object body = parseBody(post(deviceCodeUri, Map.of("client_id", clientId, "scope", scope)));
        return new DeviceCode(
                required(body, "device_code"),
                required(body, "user_code"),
                required(body, "verification_uri"),
                MiniJson.str(body, "verification_uri_complete"),
                intVal(body, "interval", 5),
                intVal(body, "expires_in", 900));
    }

    private String poll(DeviceCode dc) {
        long deadline = System.nanoTime() + Duration.ofSeconds(dc.expiresIn()).toNanos();
        int interval = Math.max(1, dc.interval());
        while (System.nanoTime() < deadline) {
            sleep(interval);
            Object body = parseBody(post(
                    tokenUri,
                    Map.of(
                            "client_id",
                            clientId,
                            "device_code",
                            dc.deviceCode(),
                            "grant_type",
                            "urn:ietf:params:oauth:grant-type:device_code")));

            String token = MiniJson.str(body, "access_token");
            if (token != null) return token;
            String error = MiniJson.str(body, "error");
            switch (error != null ? error : "") {
                case "authorization_pending" -> {
                    /* keep polling */
                }
                case "slow_down" -> interval += 5;
                case "expired_token" -> throw new AuthException("Code expired — run `jk auth login` again.");
                case "access_denied" -> throw new AuthException("Authorization was denied.");
                case "" -> throw new AuthException("Unexpected response from " + providerName + ".");
                default -> throw new AuthException("Device flow failed: " + error);
            }
        }
        throw new AuthException("Timed out waiting for authorization.");
    }

    private HttpResponse<byte[]> post(URI uri, Map<String, String> form) {
        try {
            return http.postForm(uri, form);
        } catch (IOException e) {
            throw new AuthException(
                    "Network error talking to " + providerName + " (" + uri.getHost() + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Interrupted while contacting " + providerName + ".");
        }
    }

    /**
     * The response body as a parsed JSON tree (2xx and 4xx alike — the device-flow error codes
     * this switches on arrive with a 4xx). A body that is not JSON reads as {@code null}, which
     * every accessor below treats as "no such field": a provider answering with an HTML error
     * page must surface as the flow's own "Unexpected response" arm, not as a parse crash.
     */
    /** A field the device-authorization grant requires; a response without it is the provider's fault, not a null. */
    private String required(@Nullable Object body, String key) {
        String value = MiniJson.str(body, key);
        if (value == null) throw new AuthException(providerName + " device-code response lacks \"" + key + "\".");
        return value;
    }

    private @Nullable Object parseBody(HttpResponse<byte[]> resp) {
        int status = resp.statusCode();
        if (status >= 500) {
            throw new AuthException(providerName + " returned HTTP " + status + ".");
        }
        try {
            return MiniJson.parse(new String(resp.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A JSON number field as an int; {@code def} when absent or not a number. */
    private static int intVal(@Nullable Object body, String key, int def) {
        return MiniJson.get(body, key) instanceof Number n ? n.intValue() : def;
    }

    private void sleep(int seconds) {
        try {
            sleeper.sleepSeconds(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Interrupted while waiting for authorization.");
        }
    }
}
