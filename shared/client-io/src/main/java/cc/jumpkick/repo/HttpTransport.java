// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.HttpStatusException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RepoTransport} over HTTP(S), wrapping the shared {@link Http} client (so it inherits the
 * retry / offline / backoff policy). Renders the {@link RepoCredential} to an {@code Authorization}
 * header via {@link AuthHeaders}. Covers Maven Central, Nexus, Artifactory, WebDAV, and the forge
 * package registries.
 */
public final class HttpTransport implements RepoTransport {

    private final Http http;

    public HttpTransport(Http http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        return fetchAnswer(uri, credential).map(Answer::body);
    }

    @Override
    public Optional<Answer> fetchAnswer(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.get(uri, AuthHeaders.of(credential));
        int status = response.statusCode();
        if (status == 404) return Optional.empty();
        if (!success(status)) {
            throw new HttpStatusException(status, uri);
        }
        return Optional.of(new Answer(response.body(), response.uri()));
    }

    @Override
    public Optional<InputStream> fetchStream(URI uri, RepoCredential credential)
            throws IOException, InterruptedException {
        return fetchBody(uri, credential).map(Body::in);
    }

    @Override
    public Optional<Body> fetchBody(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.getStream(uri, AuthHeaders.of(credential));
        int status = response.statusCode();
        if (status == 404) {
            drain(response.body());
            return Optional.empty();
        }
        if (!success(status)) {
            drain(response.body());
            throw new HttpStatusException(status, uri);
        }
        return Optional.of(new Body(response.body(), Http.declaredLength(response)));
    }

    /**
     * 2xx only. {@link Http} raises on every 3xx it does not follow and hands back a 304 for the
     * conditional requests it is asked to make; this transport makes none, so to it a 304 is as
     * much a failure as a 403.
     */
    private static boolean success(int status) {
        return status >= 200 && status < 300;
    }

    /** Consume and discard a non-success body so the connection can be reused. */
    private static void drain(InputStream body) throws IOException {
        try (body) {
            body.transferTo(OutputStream.nullOutputStream());
        }
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential credential)
            throws IOException, InterruptedException {
        return http.put(uri, body, putHeaders(contentType, credential)).statusCode();
    }

    @Override
    public int putFile(URI uri, Path file, String contentType, RepoCredential credential)
            throws IOException, InterruptedException {
        return http.putFile(uri, file, putHeaders(contentType, credential)).statusCode();
    }

    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"'#?]+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * The files the directory's HTML index links to directly: what Nexus, Artifactory, Reposilite
     * and a static server answer for a directory URL. An index that links to none lists nothing.
     */
    @Override
    public List<String> list(URI dir, RepoCredential credential) throws IOException, InterruptedException {
        URI base = dir.toString().endsWith("/") ? dir : URI.create(dir + "/");
        Optional<byte[]> index = fetch(base, credential);
        if (index.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        Matcher m = HREF.matcher(new String(index.get(), StandardCharsets.UTF_8));
        while (m.find()) {
            String target;
            try {
                target = base.resolve(m.group(1).strip()).toString();
            } catch (IllegalArgumentException notAUri) {
                continue;
            }
            if (!target.startsWith(base.toString())) continue;
            String name = URLDecoder.decode(
                    target.substring(base.toString().length()).replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!name.isEmpty() && !name.contains("/") && !names.contains(name)) names.add(name);
        }
        return names;
    }

    @Override
    public int delete(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        return http.delete(uri, AuthHeaders.of(credential)).statusCode();
    }

    private static Map<String, String> putHeaders(String contentType, RepoCredential credential) {
        Map<String, String> headers = new LinkedHashMap<>(AuthHeaders.of(credential));
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        return headers;
    }
}
