// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A shared cache reached over HTTP, speaking the JumpKick cache protocol, version 0: JSON control
 * messages under {@code /v0/}, small blobs in batches through the service, large blobs straight to
 * and from the store by the signed requests the service hands out.
 *
 * <p>What it sends: the action keys a build asks about, the platform and toolchain policy it asks
 * with, and, when the build writes, record texts in their shared form ({@link
 * ActionRecordText#renderShared}) and the blobs they name. The credential goes only to the
 * service's own origin; a signed request to the store carries none.
 *
 * <p>The service's answer to {@code GET /v0/capabilities} is asked once per backend (one build) and
 * kept for {@link #CAPABILITIES_TTL}: it says which protocol versions the service speaks, whether
 * this caller may write, and the batch limits every later call keeps to.
 */
public final class HttpCacheBackend implements CacheBackend {

    /** The protocol version this client speaks, sent with every request and the first path segment. */
    public static final String PROTOCOL_VERSION = "0";

    /** The header naming the protocol version a request speaks. */
    static final String PROTOCOL_HEADER = "JK-Protocol";

    /** How long a service's capabilities are taken as said. */
    static final Duration CAPABILITIES_TTL = Duration.ofMinutes(10);

    /** Headers the JDK's client sets itself and refuses from a caller; a signed request may list them. */
    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final URI base;
    private final Http api;
    private final Http direct;
    private volatile @Nullable Known known;

    /**
     * A backend for the service at {@code base} ({@code https://cache.example.com}, or under a path
     * prefix), reached through {@code http} (whose retries and timeouts the caller chose), sending
     * {@code authorization} as the {@code Authorization} of every request to {@code base}'s origin.
     */
    public HttpCacheBackend(URI base, Http http, Supplier<Optional<String>> authorization) {
        String text = Objects.requireNonNull(base, "base").toString();
        this.base = URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
        if (this.base.getHost() == null) throw new IllegalArgumentException("a cache URL needs a host: " + base);
        Optional<String> auth =
                Objects.requireNonNull(authorization, "authorization").get();
        this.direct = Objects.requireNonNull(http, "http");
        this.api = http.withAuthorization(uri -> RepositorySpec.sameOrigin(uri, this.base) ? auth : Optional.empty());
    }

    @Override
    public String name() {
        return base.getHost() + (base.getPort() == -1 ? "" : ":" + base.getPort());
    }

    /** What the service said of itself and of this caller. */
    public record Capabilities(
            List<String> versions,
            @Nullable String writeScope,
            String status,
            int batchEntries,
            long batchBytes,
            long directTransferMinBytes,
            int readsMaxFiles,
            int readsCandidates) {

        /** Whether this caller may write: a write scope, and an organisation not over its cap. */
        public boolean writes() {
            return writeScope != null && status.equals("ok");
        }

        static Capabilities parse(@Nullable Object json) {
            Object limits = MiniJson.get(json, "limits");
            Object scope = MiniJson.get(json, "scope");
            List<String> versions = new ArrayList<>();
            for (Object v : MiniJson.list(json, "protocol_versions")) if (v instanceof String s) versions.add(s);
            String status = MiniJson.str(json, "status");
            return new Capabilities(
                    versions,
                    MiniJson.str(scope, "write"),
                    status == null ? "ok" : status,
                    (int) number(limits, "batch_entries", 1000),
                    number(limits, "batch_bytes", 4L * 1024 * 1024),
                    number(limits, "direct_transfer_min_bytes", 1024L * 1024),
                    (int) number(limits, "reads_max_files", 4096),
                    (int) number(limits, "reads_candidates", 4));
        }

        private static long number(@Nullable Object node, String key, long fallback) {
            return MiniJson.get(node, key) instanceof Number n && n.longValue() > 0 ? n.longValue() : fallback;
        }
    }

    private record Known(Capabilities capabilities, long atNanos) {}

    /**
     * The service's capabilities for this caller, asked at most once per {@link #CAPABILITIES_TTL}. A service
     * that does not speak this client's version, or that says the organisation's cache is disabled,
     * is a failure like an unreachable one.
     */
    public Capabilities capabilities() throws IOException {
        Known memo = known;
        long now = Clock.SYSTEM.nanos();
        if (memo != null && now - memo.atNanos() < CAPABILITIES_TTL.toNanos()) return memo.capabilities();
        HttpResponse<byte[]> response = call(() -> api.get(endpoint("capabilities"), headers()));
        Capabilities caps = Capabilities.parse(answer(response, "capabilities"));
        if (!caps.versions().isEmpty() && !caps.versions().contains(PROTOCOL_VERSION))
            throw new IOException(
                    "the service speaks protocol " + String.join(", ", caps.versions()) + ", not " + PROTOCOL_VERSION);
        if (caps.status().equals("disabled")) throw new IOException("the service says this cache is disabled");
        known = new Known(caps, now);
        return caps;
    }

    @Override
    public boolean writable() {
        try {
            return capabilities().writes();
        } catch (IOException unreachable) {
            return false;
        }
    }

    @Override
    public Map<String, String> records(Lookup lookup) throws IOException {
        return lookup(lookup).records();
    }

    /**
     * The records found for the asked keys, and for an asked key with none, the test results the
     * service holds under a read list whose base is that key ({@code by_reads}), best first, and the
     * toolchain of a record it holds there that the reader's policy refused ({@code declined}).
     */
    @Override
    public Answer lookup(Lookup lookup) throws IOException {
        Capabilities caps = capabilities();
        Map<String, String> found = new LinkedHashMap<>();
        Map<String, List<Candidate>> byReads = new LinkedHashMap<>();
        Map<String, String> declined = new LinkedHashMap<>();
        for (List<String> keys : chunks(lookup.keys(), caps.batchEntries())) {
            Object answer = post("records/lookup", lookupBody(keys, lookup), "lookup");
            Set<String> asked = Set.copyOf(keys);
            for (Object f : MiniJson.list(answer, "found")) {
                String key = MiniJson.str(f, "key");
                String record = MiniJson.str(f, "record");
                if (key != null && record != null && asked.contains(key)) found.put(key, record);
            }
            for (Object f : MiniJson.list(answer, "by_reads")) {
                Candidate c = candidate(f);
                if (c != null
                        && asked.contains(c.reads().base())
                        && !found.containsKey(c.reads().base()))
                    byReads.computeIfAbsent(c.reads().base(), b -> new ArrayList<>())
                            .add(c);
            }
            // Optional: a service that names the records the reader's policy refused lets the
            // reader's miss say why. One that does not costs the reason, never a hit.
            for (Object d : MiniJson.list(answer, "declined")) {
                String key = MiniJson.str(d, "key");
                String toolchain = MiniJson.str(d, "toolchain");
                if (key != null && toolchain != null && asked.contains(key) && !found.containsKey(key))
                    declined.put(key, toolchain);
            }
        }
        return new Answer(found, byReads, declined);
    }

    /** A {@code by_reads} entry as a candidate, or null when it lacks a key, a record or a read list. */
    private static @Nullable Candidate candidate(@Nullable Object found) {
        String key = MiniJson.str(found, "key");
        String record = MiniJson.str(found, "record");
        Object reads = MiniJson.get(found, "reads");
        String base = MiniJson.str(reads, "base");
        if (key == null || record == null || base == null) return null;
        List<String> files = new ArrayList<>();
        for (Object f : MiniJson.list(reads, "files")) if (f instanceof String name) files.add(name);
        return files.isEmpty() ? null : new Candidate(key, record, new Reads(base, files));
    }

    /** The lookup body for {@code keys}: the doc's shape, one {@code accept} entry per distinct policy. */
    static Map<String, Object> lookupBody(List<String> keys, Lookup lookup) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keys", keys);
        body.put("platform", lookup.platform());
        body.put("follow", 0);
        Map<Accept, List<String>> byPolicy = new LinkedHashMap<>();
        for (String key : keys) {
            Accept a = lookup.accept().get(key);
            if (a != null) byPolicy.computeIfAbsent(a, x -> new ArrayList<>()).add(key);
        }
        if (!byPolicy.isEmpty()) {
            List<Object> accept = new ArrayList<>();
            byPolicy.forEach((a, ks) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("keys", ks);
                entry.put("toolchain", a.toolchain());
                if (a.level() > 0) entry.put("level", a.level());
                entry.put("match", a.match());
                if (a.requireVendor() != null || a.requireVersion() != null) {
                    Map<String, Object> require = new LinkedHashMap<>();
                    if (a.requireVendor() != null) require.put("vendor", a.requireVendor());
                    if (a.requireVersion() != null) require.put("version", a.requireVersion());
                    entry.put("require", require);
                }
                accept.add(entry);
            });
            body.put("accept", accept);
        }
        return body;
    }

    @Override
    public Set<String> missing(Map<String, Long> blobs) throws IOException {
        Capabilities caps = capabilities();
        Set<String> missing = new LinkedHashSet<>();
        for (List<String> chunk : chunks(List.copyOf(blobs.keySet()), caps.batchEntries())) {
            Object answer = post("blobs/missing", Map.of("digests", digests(chunk, blobs)), "missing");
            for (Object d : MiniJson.list(answer, "missing")) {
                String hash = MiniJson.str(d, "hash");
                if (hash != null) missing.add(hash);
            }
        }
        return missing;
    }

    @Override
    public void fetch(Map<String, Long> blobs, BlobSink sink) throws IOException {
        Capabilities caps = capabilities();
        Map<String, Long> small = new LinkedHashMap<>();
        Map<String, Long> large = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : blobs.entrySet()) {
            if (e.getValue() < 0) throw new NoSuchFileException(e.getKey(), null, "its size is not known");
            (e.getValue() < caps.directTransferMinBytes() ? small : large).put(e.getKey(), e.getValue());
        }
        for (List<String> batch : batches(small, caps)) {
            Object answer = post("blobs/batch-read", Map.of("digests", digests(batch, small)), "blob read");
            Set<String> wanted = new LinkedHashSet<>(batch);
            for (Object b : MiniJson.list(answer, "blobs")) {
                String hash = MiniJson.str(b, "hash");
                String data = MiniJson.str(b, "data");
                if (hash == null || data == null || !wanted.remove(hash)) continue;
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(data);
                } catch (IllegalArgumentException notBase64) {
                    throw new IOException("blob " + hash + " is not base64", notBase64);
                }
                sink.accept(hash, new ByteArrayInputStream(bytes));
            }
            if (!wanted.isEmpty())
                throw new NoSuchFileException(wanted.iterator().next(), null, "not held");
        }
        for (List<String> chunk : chunks(List.copyOf(large.keySet()), caps.batchEntries())) {
            Object answer = post("blobs/download-urls", Map.of("blobs", digests(chunk, large)), "download");
            Set<String> wanted = new LinkedHashSet<>(chunk);
            for (Object r : MiniJson.list(answer, "requests")) {
                String hash = MiniJson.str(r, "hash");
                if (hash == null || !wanted.remove(hash)) continue;
                URI url = signedUrl(r);
                HttpResponse<InputStream> got = call(() -> direct.getStream(url, signedHeaders(r)));
                try (InputStream body = got.body()) {
                    if (got.statusCode() / 100 != 2)
                        throw new IOException("the store answered " + got.statusCode() + " for blob " + hash);
                    sink.accept(hash, body);
                }
            }
            if (!wanted.isEmpty())
                throw new NoSuchFileException(wanted.iterator().next(), null, "not held");
        }
    }

    @Override
    public void putBlobs(Map<String, Path> blobs) throws IOException {
        Capabilities caps = capabilities();
        Map<String, Long> small = new LinkedHashMap<>();
        Map<String, Long> large = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : blobs.entrySet()) {
            long size = Files.size(e.getValue());
            (size < caps.directTransferMinBytes() ? small : large).put(e.getKey(), size);
        }
        for (List<String> batch : batches(small, caps)) {
            List<Object> entries = new ArrayList<>();
            for (String hash : batch) {
                byte[] bytes = Files.readAllBytes(blobs.get(hash));
                Map<String, Object> blob = new LinkedHashMap<>();
                blob.put("hash", hash);
                blob.put("size", (long) bytes.length);
                blob.put("data", Base64.getEncoder().encodeToString(bytes));
                entries.add(blob);
            }
            post("blobs/batch-upload", Map.of("blobs", entries), "blob upload");
        }
        for (List<String> chunk : chunks(List.copyOf(large.keySet()), caps.batchEntries())) {
            Object answer = post("blobs/upload-urls", Map.of("blobs", digests(chunk, large)), "upload");
            for (Object r : MiniJson.list(answer, "requests")) {
                String hash = MiniJson.str(r, "hash");
                Path file = hash == null ? null : blobs.get(hash);
                if (file == null) continue;
                URI url = signedUrl(r);
                HttpResponse<byte[]> put = call(() -> direct.putFile(url, file, signedHeaders(r)));
                if (put.statusCode() / 100 != 2) {
                    String m = "blob " + hash + ": " + put.statusCode() + " from the store";
                    throw put.statusCode() == 429 || put.statusCode() / 100 == 5
                            ? new Unavailable(m)
                            : new IOException(m);
                }
            }
            Object committed = post("blobs/commit", Map.of("blobs", digests(chunk, large)), "commit");
            List<?> absent = MiniJson.list(committed, "absent");
            if (!absent.isEmpty())
                throw new IOException("the service did not find " + absent.size() + " uploaded blob(s), first "
                        + MiniJson.str(absent.getFirst(), "hash"));
        }
    }

    @Override
    public void putRecord(String key, String recordText) throws IOException {
        putRecord(key, recordText, null);
    }

    /**
     * As {@link #putRecord(String, String)}, with the suite's read list when it fits the service's
     * limit ({@code reads_max_files}); a longer one is left off, and the result is still shared
     * with hosts that know the list.
     */
    @Override
    public void putRecord(String key, String recordText, @Nullable Reads reads) throws IOException {
        if (!ActionRecordText.isSha256(key)) throw new Skipped("not an action key: " + key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("record", recordText);
        if (reads != null
                && !reads.files().isEmpty()
                && reads.files().size() <= capabilities().readsMaxFiles()) {
            Map<String, Object> list = new LinkedHashMap<>();
            list.put("base", reads.base());
            list.put("files", reads.files());
            body.put("reads", list);
        }
        byte[] bytes = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
        URI uri = endpoint("records/" + key);
        HttpResponse<byte[]> response = call(() -> api.put(uri, bytes, jsonHeaders()));
        if (response.statusCode() / 100 == 2) return;
        Problem p = Problem.of(response);
        switch (p.code()) {
            // The first record under this key and toolchain stays: nothing to do.
            case "conflict" -> {}
            case "blobs_missing" -> throw new BlobsMissing(p.message("record " + key), p.missing());
            case "too_large", "invalid", "refused_kind", "red_result", "signature_invalid" ->
                throw new Skipped(p.message("record " + key));
            default -> throw p.failure("record " + key);
        }
    }

    private @Nullable Object post(String path, Object body, String what) throws IOException {
        byte[] bytes = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
        URI uri = endpoint(path);
        return answer(call(() -> api.post(uri, bytes, jsonHeaders())), what);
    }

    /** The parsed body of a 2xx answer; a problem document, or any other answer, as a failure naming it. */
    private static @Nullable Object answer(HttpResponse<byte[]> response, String what) throws IOException {
        if (response.statusCode() / 100 != 2) {
            Problem p = Problem.of(response);
            if (p.code().equals("too_large") || p.code().equals("digest_mismatch")) throw new Skipped(p.message(what));
            throw p.failure(what);
        }
        try {
            return MiniJson.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            throw new IOException(what + ": the answer is not JSON", unreadable);
        }
    }

    /** An RFC 9457 problem document's {@code code}, {@code detail} and, for {@code blobs_missing}, the blobs. */
    record Problem(int status, String code, String detail, List<String> missing) {

        static Problem of(HttpResponse<byte[]> response) {
            Object json = null;
            try {
                json = MiniJson.parse(new String(response.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException notJson) {
                // A proxy's page, a bare status: the status says what there is to say.
            }
            String code = MiniJson.str(json, "code");
            String detail = MiniJson.str(json, "detail");
            List<String> missing = new ArrayList<>();
            for (Object d : MiniJson.list(json, "missing")) {
                String hash = d instanceof String s ? s : MiniJson.str(d, "hash");
                if (hash != null) missing.add(hash);
            }
            return new Problem(
                    response.statusCode(),
                    code == null ? "http_" + response.statusCode() : code,
                    detail == null ? "" : oneLine(detail),
                    missing);
        }

        /**
         * This problem as the failure a caller acts on: {@link WritesRefused} for a refusal every
         * later write would meet, {@link Unavailable} for one worth retrying (section 7), else a plain
         * failure.
         */
        IOException failure(String what) {
            String m = message(what);
            return switch (code) {
                case "forbidden", "read_only", "unauthenticated", "unsupported_protocol" -> new WritesRefused(m);
                case "unavailable", "rate_limited" -> new Unavailable(m);
                default -> status == 429 || status / 100 == 5 ? new Unavailable(m) : new IOException(m);
            };
        }

        String message(String what) {
            return what + ": " + status + " " + code + (detail.isEmpty() ? "" : " (" + detail + ")");
        }

        private static String oneLine(String s) {
            String flat = s.replace('\n', ' ').replace('\r', ' ').strip();
            return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
        }
    }

    @FunctionalInterface
    private interface Request<T> {
        HttpResponse<T> send() throws IOException, InterruptedException;
    }

    private static <T> HttpResponse<T> call(Request<T> request) throws IOException {
        try {
            return request.send();
        } catch (Http.StatusException answered) {
            // The client hands a 5xx back as a failure, not a response: one the protocol retries.
            Unavailable retryable =
                    new Unavailable(answered.getMessage() + ": " + answered.status() + " from the service");
            retryable.initCause(answered);
            throw retryable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private URI endpoint(String path) {
        return URI.create(base + "/v" + PROTOCOL_VERSION + "/" + path);
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(PROTOCOL_HEADER, PROTOCOL_VERSION);
        h.put("User-Agent", "jk/" + JkVersion.VERSION);
        h.put("Accept", "application/json");
        return h;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> h = headers();
        h.put("Content-Type", "application/json");
        return h;
    }

    private static List<Object> digests(List<String> hashes, Map<String, Long> sizes) {
        List<Object> out = new ArrayList<>();
        for (String hash : hashes) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("hash", hash);
            d.put("size", sizes.get(hash));
            out.add(d);
        }
        return out;
    }

    private static URI signedUrl(@Nullable Object request) throws IOException {
        String url = MiniJson.str(request, "url");
        URI uri;
        try {
            uri = url == null ? null : URI.create(url);
        } catch (IllegalArgumentException malformed) {
            uri = null;
        }
        String scheme =
                uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri == null || !(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null)
            throw new IOException("the service handed out a request jk cannot send: " + url);
        return uri;
    }

    /** A signed request's headers, as given, less those the JDK's client sets itself. */
    private static Map<String, String> signedHeaders(@Nullable Object request) {
        Map<String, String> out = new LinkedHashMap<>();
        if (MiniJson.get(request, "headers") instanceof Map<?, ?> given) {
            given.forEach((k, v) -> {
                if (k instanceof String name
                        && v instanceof String value
                        && !RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) out.put(name, value);
            });
        }
        return out;
    }

    private static List<List<String>> chunks(List<String> all, int size) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    /** {@code sizes}' digests in batches of at most the entries and decoded bytes a batch may hold. */
    private static List<List<String>> batches(Map<String, Long> sizes, Capabilities caps) {
        List<List<String>> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long bytes = 0;
        for (Map.Entry<String, Long> e : sizes.entrySet()) {
            if (!current.isEmpty()
                    && (current.size() == caps.batchEntries() || bytes + e.getValue() > caps.batchBytes())) {
                out.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(e.getKey());
            bytes += e.getValue();
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }
}
