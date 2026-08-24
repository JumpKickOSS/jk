// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * On-disk {@code maven-metadata.xml} cache (mutable index — not content-addressed).
 *
 * <p><strong>Local first:</strong> within {@link #DEFAULT_TTL} (24h, Maven's daily policy) a
 * cached body is returned with <em>no</em> HTTP. Past TTL, conditional GET ({@code ETag} /
 * {@code Last-Modified}); 304 restarts the TTL. Network/429 errors reuse a stale copy; 404 →
 * {@link MavenRepo.ArtifactNotFoundException}.
 *
 * <p>{@link #withForceRevalidate} / {@code -F} skip the TTL short-circuit (still conditional
 * GET when validators exist). Reserved for {@code jk update} and explicit force — not every
 * {@code jk lock}, so back-to-back locks do not hammer Central.
 */
public final class MavenMetadataCache {

    /** Maven's own default release-metadata update policy is daily; match it. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * When true on the calling thread, {@link #fetch} skips the TTL short-circuit and revalidates
     * (conditional GET when validators exist). Used by {@code jk update} and {@code -F}/{@code
     * --force}; normal {@code jk lock} leaves the TTL alone.
     */
    private static final ThreadLocal<Boolean> FORCE_REVALIDATE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Http http;
    private final Path dir;
    private final Duration ttl;

    public MavenMetadataCache(Http http, Path dir, Duration ttl) {
        this.http = Objects.requireNonNull(http, "http");
        this.dir = Objects.requireNonNull(dir, "dir");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /**
     * Run {@code body} with metadata TTL bypassed on this thread (conditional GET still applies).
     * Nested calls keep the outer flag.
     */
    public static <T> T withForceRevalidate(Callable<T> body) throws Exception {
        Boolean prev = FORCE_REVALIDATE.get();
        FORCE_REVALIDATE.set(Boolean.TRUE);
        // Force means do not trust process-wide resolve memos computed against a prior view.
        // Concrete clears live on the types themselves so this module stays free of resolver deps.
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        try {
            // KMP process cache is in resolver — clear via reflective no-op if absent (tests/io-only).
            try {
                Class.forName("cc.jumpkick.resolver.KmpRedirects")
                        .getMethod("clearProcessCache")
                        .invoke(null);
            } catch (ReflectiveOperationException ignored) {
                // io unit tests without resolver on classpath
            }
            return body.call();
        } finally {
            FORCE_REVALIDATE.set(prev);
        }
    }

    /** True when this thread is inside {@link #withForceRevalidate}. */
    static boolean forceRevalidate() {
        return Boolean.TRUE.equals(FORCE_REVALIDATE.get());
    }

    /** Metadata bytes for {@code uri}; 404 → {@link MavenRepo.ArtifactNotFoundException}. */
    public byte[] fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        Path body = dir.resolve(Hashing.sha256Hex(uri.toString()));
        Path meta = body.resolveSibling(body.getFileName() + ".h");

        // -F / withForceRevalidate (jk update) skip the TTL window. Unchanged indexes still
        // cost only a conditional GET (304); warm TTL hits never leave the disk.
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false) || forceRevalidate();
        if (!force && fresh(body)) {
            return Files.readAllBytes(body);
        }
        Map<String, String> headers = new LinkedHashMap<>(AuthHeaders.of(credential));
        addValidators(meta, headers);
        try {
            HttpResponse<byte[]> resp = http.get(uri, headers);
            int status = resp.statusCode();
            if (status == 304 && Files.isRegularFile(body)) {
                touch(body); // revalidated: restart the TTL
                return Files.readAllBytes(body);
            }
            if (status == 200) {
                store(body, meta, resp);
                // A fresh index off the network — a 304 revalidation costs no payload, so isn't metered.
                cc.jumpkick.config.SessionContext.current().io().remoteDown(body);
                return resp.body();
            }
            if (status == 404) {
                throw new MavenRepo.ArtifactNotFoundException("not found: " + uri);
            }
            // 4xx (incl. 429) / other: reuse a stale copy rather than fail the
            // resolve when the server won't hand us a fresh index right now.
            if (Files.isRegularFile(body)) {
                return Files.readAllBytes(body);
            }
            throw new IOException("HTTP " + status + " fetching " + uri);
        } catch (MavenRepo.ArtifactNotFoundException notFound) {
            throw notFound; // a real miss, not a transport hiccup
        } catch (IOException networkError) {
            if (Files.isRegularFile(body)) {
                return Files.readAllBytes(body); // offline / unreachable: stale-but-usable
            }
            throw networkError;
        }
    }

    private boolean fresh(Path body) throws IOException {
        if (ttl.isZero() || ttl.isNegative()) return false;
        if (!Files.isRegularFile(body) || Files.size(body) == 0) return false;
        Instant mtime = Files.getLastModifiedTime(body).toInstant();
        return Duration.between(mtime, Instant.now()).compareTo(ttl) < 0;
    }

    /** Echo back the validators stored alongside a cached body (ETag, then Last-Modified). */
    private static void addValidators(Path meta, Map<String, String> headers) throws IOException {
        if (!Files.isRegularFile(meta)) return;
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        String etag = lines.isEmpty() ? "" : lines.get(0);
        String lastModified = lines.size() > 1 ? lines.get(1) : "";
        if (!etag.isBlank()) headers.put("If-None-Match", etag);
        if (!lastModified.isBlank()) headers.put("If-Modified-Since", lastModified);
    }

    private void store(Path body, Path meta, HttpResponse<byte[]> resp) throws IOException {
        Files.createDirectories(dir);
        writeAtomic(body, resp.body());
        String etag = resp.headers().firstValue("ETag").orElse("");
        String lastModified = resp.headers().firstValue("Last-Modified").orElse("");
        writeAtomic(meta, (etag + "\n" + lastModified).getBytes(StandardCharsets.UTF_8));
    }

    private void writeAtomic(Path target, byte[] data) throws IOException {
        AtomicWrites.replace(target, data);
    }

    private static void touch(Path body) throws IOException {
        Files.setLastModifiedTime(body, FileTime.from(Instant.now()));
    }
}
