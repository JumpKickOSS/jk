// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.RepositorySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The Sonatype Central Portal publisher API: one multipart upload of a {@link CentralBundle}, then
 * a status poll until the Portal has ruled. Authentication is a bearer token — the Portal's user
 * token, which is the base64 of {@code name:password}; a {@link RepoCredential.Basic} is encoded
 * here, a {@link RepoCredential.Bearer} is taken as already encoded.
 *
 * <p>Endpoints: {@code POST /api/v1/publisher/upload?name=…&publishingType=…} answers the
 * deployment id as its body; {@code POST /api/v1/publisher/status?id=…} answers JSON with {@code
 * deploymentState} and, once validation has failed, {@code errors} — a map of scope to messages.
 */
public final class CentralPortal {

    /** The production Portal; {@code --repo-url} points at another one. */
    public static final URI DEFAULT_URL = URI.create("https://central.sonatype.com/");

    /**
     * The credential id {@code jk publish --central} reads from the store and the environment: the
     * repository's own name, so the Portal's token lives where Central's does.
     */
    public static final String CREDENTIAL_ID = RepositorySpec.CENTRAL;

    /** How long a poll waits for a verdict before the run gives up on the deployment. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private static final long FIRST_POLL_MILLIS = 500;
    private static final long MAX_POLL_MILLIS = 5_000;

    /**
     * What the Portal does once the bundle validates: {@code USER_MANAGED} parks the deployment
     * for a click in the Portal UI; {@code AUTOMATIC} releases it to Central.
     */
    public enum PublishingType {
        USER_MANAGED,
        AUTOMATIC;

        /** {@code user-managed} / {@code automatic} in any case; anything else is a usage error. */
        public static PublishingType parse(@Nullable String text) {
            if (text == null || text.isBlank()) return USER_MANAGED;
            String key = text.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (PublishingType t : values()) if (t.name().equals(key)) return t;
            throw new IllegalArgumentException("--publishing-type must be user-managed or automatic, got: " + text);
        }

        /** The spelling the CLI and the docs use. */
        public String flag() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** One status answer: the state and every error message the Portal listed, any scope. */
    public record Status(String state, List<String> errors) {
        public Status {
            Objects.requireNonNull(state, "state");
            errors = List.copyOf(errors);
        }

        public boolean failed() {
            return "FAILED".equals(state);
        }

        /** Whether the poll can stop: the Portal will not move this deployment on its own any more. */
        public boolean terminal(PublishingType type) {
            return switch (state) {
                case "FAILED", "PUBLISHED" -> true;
                case "VALIDATED" -> type == PublishingType.USER_MANAGED;
                default -> false;
            };
        }
    }

    /** Waits between polls; a test hands in one that does not. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final URI base;
    private final String bearer;
    private final Http http;

    public CentralPortal(URI base, RepoCredential credential, Http http) throws IOException {
        this.base = normalize(Objects.requireNonNull(base, "base"));
        this.bearer = bearerToken(Objects.requireNonNull(credential, "credential"));
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * The Portal's bearer token for {@code credential}: a stored user token as-is, or a
     * name/password pair encoded the way the Portal documents.
     */
    static String bearerToken(RepoCredential credential) throws IOException {
        return switch (credential) {
            case RepoCredential.Bearer b -> b.token();
            case RepoCredential.Basic b ->
                Base64.getEncoder()
                        .encodeToString((b.username() + ":" + b.password()).getBytes(StandardCharsets.UTF_8));
            case RepoCredential.Anonymous ignored ->
                throw new IOException(
                        "the Central Portal needs a user token: run `jk repo login "
                                + CREDENTIAL_ID + " --url " + DEFAULT_URL
                                + "` with the token's name as --username and its password on stdin (or export JK_REPO_CENTRAL_USERNAME / JK_REPO_CENTRAL_PASSWORD with JK_REPO_CENTRAL_HOST=central.sonatype.com)");
        };
    }

    /** Upload {@code bundle} as deployment {@code name}; returns the id the Portal assigned. */
    public String upload(byte[] bundle, String name, PublishingType type) throws IOException, InterruptedException {
        String boundary = "----jk-central-" + UUID.randomUUID();
        byte[] body = multipart(boundary, name + ".zip", bundle);
        URI uri = base.resolve("api/v1/publisher/upload?name=" + encode(name) + "&publishingType=" + type.name());
        HttpResponse<byte[]> response = http.post(
                uri,
                body,
                Map.of(
                        "Authorization", "Bearer " + bearer,
                        "Content-Type", "multipart/form-data; boundary=" + boundary,
                        "Accept", "text/plain"));
        String text = new String(response.body(), StandardCharsets.UTF_8).trim();
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Central Portal upload returned HTTP " + response.statusCode() + " for " + uri
                    + (text.isEmpty() ? "" : ": " + clip(text)));
        }
        if (text.isEmpty()) {
            throw new IOException(
                    "Central Portal upload returned no deployment id (HTTP " + response.statusCode() + ")");
        }
        return text;
    }

    /** One status read for {@code deploymentId}. */
    public Status status(String deploymentId) throws IOException, InterruptedException {
        URI uri = base.resolve("api/v1/publisher/status?id=" + encode(deploymentId));
        HttpResponse<byte[]> response =
                http.post(uri, new byte[0], Map.of("Authorization", "Bearer " + bearer, "Accept", "application/json"));
        String text = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Central Portal status returned HTTP " + response.statusCode() + " for deployment "
                    + deploymentId + (text.isBlank() ? "" : ": " + clip(text.trim())));
        }
        return parseStatus(text, deploymentId);
    }

    /**
     * Poll {@link #status} until the deployment is {@linkplain Status#terminal terminal} for
     * {@code type}, backing off from half a second to five, and give up after {@code timeout} on
     * {@code clock} naming the id and the last state seen.
     */
    public Status awaitTerminal(
            String deploymentId, PublishingType type, Duration timeout, Clock clock, Sleeper sleeper)
            throws IOException, InterruptedException {
        long deadline = clock.nanos() + timeout.toNanos();
        long wait = FIRST_POLL_MILLIS;
        Status last = status(deploymentId);
        while (!last.terminal(type)) {
            if (clock.nanos() >= deadline) {
                throw new IOException("Central Portal deployment " + deploymentId + " is still " + last.state()
                        + " after " + timeout.toMinutes() + " minutes; check it at " + base + "publishing/deployments");
            }
            sleeper.sleep(wait);
            wait = Math.min(wait * 2, MAX_POLL_MILLIS);
            last = status(deploymentId);
        }
        return last;
    }

    /** The status document's state and messages; a body that is not the Portal's shape is an error. */
    static Status parseStatus(String json, String deploymentId) throws IOException {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (RuntimeException e) {
            throw new IOException(
                    "Central Portal status for " + deploymentId + " is not JSON: " + clip(json.trim()), e);
        }
        String state = MiniJson.str(root, "deploymentState");
        if (state == null) {
            throw new IOException(
                    "Central Portal status for " + deploymentId + " names no deploymentState: " + clip(json.trim()));
        }
        return new Status(state, errorsOf(MiniJson.get(root, "errors")));
    }

    /**
     * Every message under {@code errors}, whatever the Portal grouped it by: a map of scope to
     * message lists, a bare list, or a single string.
     */
    static List<String> errorsOf(@Nullable Object errors) {
        List<String> out = new ArrayList<>();
        collect(errors, out);
        return out;
    }

    private static void collect(@Nullable Object node, List<String> out) {
        if (node instanceof String s) {
            if (!s.isBlank()) out.add(s);
        } else if (node instanceof List<?> list) {
            for (Object o : list) collect(o, out);
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) collect(v, out);
        }
    }

    private static byte[] multipart(String boundary, String filename, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 256);
        out.write(("--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"bundle\"; filename=\"" + filename
                        + "\"\r\n" + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clip(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }
}
