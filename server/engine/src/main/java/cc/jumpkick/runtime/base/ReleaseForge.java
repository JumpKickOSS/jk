// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** What the forge release stores share: paged lists, hashing a download, the tagged commit. */
final class ReleaseForge {

    private static final int MAX_PAGES = 50;
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final Duration GIT_WAIT = Duration.ofSeconds(10);

    private ReleaseForge() {}

    /** A file's SHA-256, computed only when a same-named file needs comparing. */
    interface Sha {
        String get() throws IOException;
    }

    /** A file's body, opened only when it is sent. */
    interface Body {
        HttpRequest.BodyPublisher get() throws IOException;
    }

    /** The one-line exception a forge's refusal of {@code what} stands for. */
    interface Refusal {
        IOException of(String what, HttpResponse<?> response);
    }

    /** Every element of a paged JSON array, following {@code Link: rel="next"}. */
    static List<Object> pages(Http http, URI first, Map<String, String> headers, Refusal refused)
            throws IOException, InterruptedException {
        List<Object> out = new ArrayList<>();
        URI next = first;
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            HttpResponse<byte[]> response = http.get(next, headers);
            if (response.statusCode() != 200) throw refused.of("GET " + next.getPath(), response);
            Object parsed = MiniJson.parseRelaxed(new String(response.body(), StandardCharsets.UTF_8));
            if (parsed instanceof List<?> list) out.addAll(list);
            next = response.headers()
                    .firstValue("link")
                    .map(NEXT::matcher)
                    .filter(Matcher::find)
                    .map(m -> URI.create(m.group(1)))
                    .orElse(null);
        }
        return out;
    }

    /** One JSON object of a response body, or {@code null} when it holds none. */
    static @Nullable Object object(byte[] body) {
        return MiniJson.parseRelaxed(new String(body, StandardCharsets.UTF_8));
    }

    /** The size and SHA-256 of a download, read through once. */
    static ReleaseStore.Held hash(HttpResponse<InputStream> response, String forge, String name) throws IOException {
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(forge + " answered " + response.statusCode() + " for " + name);
            }
            MessageDigest sha = Hashing.newSha256();
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                sha.update(buffer, 0, n);
                size += n;
            }
            return new ReleaseStore.Held(size, Hashing.hex(sha.digest()));
        }
    }

    /** The other-bytes refusal: {@code name} is in {@code where} already and differs. */
    static IOException otherBytes(String name, String where) {
        return new IOException(name + " is already in " + where + " with other bytes; raise the version in jk.toml");
    }

    static long number(Object node, String key) {
        return MiniJson.get(node, key) instanceof Number n ? n.longValue() : -1;
    }

    /** A 429 or a 403 with {@code Retry-After}, said as when to try again; {@code null} otherwise. */
    static @Nullable IOException retryLater(String forge, HttpResponse<?> response) {
        int status = response.statusCode();
        var retryAfter = response.headers().firstValue("retry-after");
        if (status == 429 || (status == 403 && retryAfter.isPresent())) {
            return new IOException(forge + " rate limit reached; try again "
                    + retryAfter.map(s -> "in " + s.strip() + " s").orElse("later"));
        }
        return null;
    }

    /** The commit {@code tag} names in {@code dir}'s checkout, else {@code HEAD}'s; {@code null} when git cannot say. */
    static @Nullable String commitOf(Path dir, String tag) {
        for (String rev : List.of(tag + "^{commit}", "HEAD")) {
            try {
                ProcessBuilder pb = new ProcessBuilder(GitCommand.of(
                                "-C", dir.toString(), "rev-parse", "--verify", "--quiet", "--end-of-options", rev))
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                Processes.Captured c = Processes.capture(pb, JobWorkers.startDetached(pb), GIT_WAIT);
                String out = c.output().strip();
                if (c.ok() && out.matches("[0-9a-f]{40}([0-9a-f]{24})?")) return out;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
