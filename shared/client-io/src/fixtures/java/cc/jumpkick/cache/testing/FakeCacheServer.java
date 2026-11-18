// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache.testing;

import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A stub of the JumpKick cache service, speaking protocol version 0 over loopback HTTP, in memory:
 * capabilities, the blob calls (small blobs in batches, large ones through "signed" requests to a
 * second server standing in for the object store, another origin), batched record lookups with the
 * reader's toolchain policy and results found by read list, and record puts with the write rules.
 * It keeps a log of every call and can be told to stall, to serve a blob's bytes altered, or to go
 * away, so a test can see what a client sent and how it fares.
 *
 * <p>Tokens: {@link #WRITER} writes the trusted scope; {@link #READER} reads it and writes nothing,
 * as a fork's pull request does. Any other token is refused.
 */
public final class FakeCacheServer implements AutoCloseable {

    /** A token whose caller writes (CI on a protected branch). */
    public static final String WRITER = "fake-ci-token";

    /** A token whose caller only reads (a fork's pull request). */
    public static final String READER = "fake-fork-token";

    private static final Pattern OUTPUT = Pattern.compile("^OUTPUT ([0-9a-f]{64})/(\\d+) ", Pattern.MULTILINE);
    private static final Pattern PORTABLE = Pattern.compile("[a-z][a-z0-9-]*:(?!/)[^\\\\]+");

    private final HttpServer api;
    private final HttpServer objects;
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final Map<String, byte[]> uploaded = new ConcurrentHashMap<>();
    private final Map<String, List<Stored>> records = new ConcurrentHashMap<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Object> lookups = Collections.synchronizedList(new ArrayList<>());
    private final List<String> storeAuthorizations = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> tampered = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong byReadsServed = new AtomicLong();
    private volatile long directMin = 1024 * 1024;
    private volatile long stallMillis;

    /** One record under a key: its text, write class, toolchain and read list. */
    public record Stored(
            String text,
            String writes,
            @Nullable String toolchain,
            @Nullable Object reads,
            long seq) {}

    private FakeCacheServer() throws IOException {
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        api = HttpServer.create(loopback, 0);
        objects = HttpServer.create(loopback, 0);
        api.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        objects.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        api.createContext("/", this::serveApi);
        objects.createContext("/", this::serveObject);
        api.start();
        objects.start();
    }

    /** A server listening on two loopback ports: the service, and the object store its signed requests name. */
    public static FakeCacheServer start() throws IOException {
        return new FakeCacheServer();
    }

    /** The service's base URL. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + api.getAddress().getPort());
    }

    /** {@code host:port} of the service: the id its credential is kept under. */
    public String hostId() {
        return "127.0.0.1:" + api.getAddress().getPort();
    }

    /** Blobs of {@code bytes} and more move through signed requests; smaller ones in batches. */
    public FakeCacheServer directTransferFrom(long bytes) {
        directMin = bytes;
        return this;
    }

    /** Hold every record lookup this long before answering. */
    public FakeCacheServer stall(long millis) {
        stallMillis = millis;
        return this;
    }

    /** Serve blob {@code hash} with its first byte flipped from now on. */
    public void tamper(String hash) {
        tampered.add(hash);
    }

    /** Every call so far, as {@code METHOD /path caller}, {@code caller} being {@code writer}, {@code reader} or {@code anonymous}. */
    public List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** The body of every record lookup so far, as parsed JSON. */
    public List<Object> lookups() {
        synchronized (lookups) {
            return List.copyOf(lookups);
        }
    }

    /** The {@code Authorization} each request to the object store carried, {@code -} for none. */
    public List<String> storeAuthorizations() {
        synchronized (storeAuthorizations) {
            return List.copyOf(storeAuthorizations);
        }
    }

    /** How many results lookups have answered by read list ({@code by_reads}) so far. */
    public long byReadsServed() {
        return byReadsServed.get();
    }

    /** The blobs held, by sha256. */
    public Map<String, byte[]> blobs() {
        return blobs;
    }

    /** The records held under each key. */
    public Map<String, List<Stored>> records() {
        return records;
    }

    /** The records held whose write class is {@code writes}. */
    public List<Stored> recordsOf(String writes) {
        List<Stored> out = new ArrayList<>();
        for (List<Stored> under : records.values()) {
            synchronized (under) {
                for (Stored s : under) if (s.writes().equals(writes)) out.add(s);
            }
        }
        return out;
    }

    /** Hold {@code text} under {@code key} as if a writer had put it, blobs included, with {@code reads} when not null. */
    public void seed(String key, String text, Map<String, byte[]> payload, @Nullable Object reads) {
        blobs.putAll(payload);
        String writes = header(text, "WRITES");
        records.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Stored(
                        text,
                        writes == null ? "outputs" : writes,
                        header(text, "TOOLCHAIN"),
                        reads,
                        seq.incrementAndGet()));
    }

    @Override
    public void close() {
        api.stop(0);
        objects.stop(0);
    }

    // ---- the service ----------------------------------------------------------------------------

    private void serveApi(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String caller = caller(ex.getRequestHeaders().getFirst("Authorization"));
            calls.add(ex.getRequestMethod() + " " + path + " " + caller);
            String protocol = ex.getRequestHeaders().getFirst("JK-Protocol");
            if (protocol != null && !protocol.equals("0")) {
                problem(ex, 400, "unsupported_protocol", "this service speaks protocol 0", List.of());
                return;
            }
            if (caller.equals("anonymous")) {
                problem(ex, 401, "unauthenticated", "the token identifies no one", List.of());
                return;
            }
            boolean writer = caller.equals("writer");
            Object body = readJson(ex);
            switch (ex.getRequestMethod() + " " + path) {
                case "GET /v0/capabilities" -> json(ex, 200, capabilities(writer));
                case "POST /v0/blobs/missing" ->
                    json(ex, 200, Map.of("missing", absent(MiniJson.list(body, "digests"))));
                case "POST /v0/blobs/batch-upload" -> batchUpload(ex, writer, body);
                case "POST /v0/blobs/batch-read" -> batchRead(ex, body);
                case "POST /v0/blobs/upload-urls" -> uploadUrls(ex, writer, body);
                case "POST /v0/blobs/commit" -> commit(ex, writer, body);
                case "POST /v0/blobs/download-urls" -> downloadUrls(ex, body);
                case "POST /v0/records/lookup" -> lookup(ex, body);
                default -> {
                    if (ex.getRequestMethod().equals("PUT") && path.startsWith("/v0/records/"))
                        put(ex, writer, path.substring("/v0/records/".length()), body);
                    else problem(ex, 404, "not_found", "no such call: " + path, List.of());
                }
            }
        } catch (RuntimeException e) {
            problem(ex, 503, "unavailable", String.valueOf(e), List.of());
        }
    }

    private static String caller(@Nullable String authorization) {
        if (("Bearer " + WRITER).equals(authorization)) return "writer";
        if (("Bearer " + READER).equals(authorization)) return "reader";
        return "anonymous";
    }

    /** The capabilities answer as section 3 of the protocol document shows it, for this caller. */
    private Map<String, Object> capabilities(boolean writer) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("read", List.of("trusted"));
        if (writer) scope.put("write", "trusted");
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("batch_entries", 1000);
        limits.put("batch_bytes", 4194304);
        limits.put("direct_transfer_min_bytes", directMin);
        limits.put("record_max_bytes", 1048576);
        limits.put("blob_max_bytes", 5368709120L);
        limits.put("signed_url_ttl_seconds", 900);
        limits.put("follow_max_depth", 8);
        limits.put("reads_max_files", 4096);
        limits.put("reads_candidates", 4);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("protocol_versions", List.of("0"));
        caps.put("digest_functions", List.of("sha256"));
        caps.put("compression", List.of("identity"));
        caps.put("organisation", "org_fake");
        caps.put("identity", Map.of("kind", writer ? "ci-protected" : "ci-fork", "subject", "fake"));
        caps.put("scope", scope);
        caps.put("dimensions", List.of("platform", "toolchain"));
        caps.put("limits", limits);
        caps.put("status", "ok");
        return caps;
    }

    private List<Object> absent(List<?> digests) {
        List<Object> missing = new ArrayList<>();
        for (Object d : digests) {
            byte[] held = blobs.get(String.valueOf(MiniJson.str(d, "hash")));
            if (held == null || held.length != size(d)) missing.add(d);
        }
        return missing;
    }

    private void batchUpload(HttpExchange ex, boolean writer, @Nullable Object body) throws IOException {
        if (!writer) {
            problem(ex, 403, "forbidden", "ci-fork callers write nothing", List.of());
            return;
        }
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        List<Object> stored = new ArrayList<>();
        for (Object b : MiniJson.list(body, "blobs")) {
            String hash = String.valueOf(MiniJson.str(b, "hash"));
            byte[] bytes = Base64.getDecoder().decode(String.valueOf(MiniJson.str(b, "data")));
            if (size(b) >= directMin) {
                problem(ex, 413, "too_large", "blob " + hash + " moves by signed URL", List.of());
                return;
            }
            if (!sha256(bytes).equals(hash) || bytes.length != size(b)) {
                problem(ex, 422, "digest_mismatch", "blob " + hash + " does not match its digest", List.of());
                return;
            }
            decoded.put(hash, bytes);
            stored.add(Map.of("hash", hash, "size", (long) bytes.length));
        }
        blobs.putAll(decoded);
        json(ex, 200, Map.of("stored", stored));
    }

    private void batchRead(HttpExchange ex, @Nullable Object body) throws IOException {
        List<Object> out = new ArrayList<>();
        List<Object> missing = new ArrayList<>();
        for (Object d : MiniJson.list(body, "digests")) {
            String hash = String.valueOf(MiniJson.str(d, "hash"));
            if (size(d) >= directMin) {
                problem(ex, 413, "too_large", "blob " + hash + " moves by signed URL, not in a batch", List.of());
                return;
            }
            byte[] bytes = served(hash);
            if (bytes == null) missing.add(d);
            else
                out.add(Map.of(
                        "hash",
                        hash,
                        "size",
                        (long) bytes.length,
                        "data",
                        Base64.getEncoder().encodeToString(bytes)));
        }
        json(ex, 200, Map.of("blobs", out, "missing", missing));
    }

    private void uploadUrls(HttpExchange ex, boolean writer, @Nullable Object body) throws IOException {
        if (!writer) {
            problem(ex, 403, "forbidden", "ci-fork callers write nothing", List.of());
            return;
        }
        List<Object> requests = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Object d : MiniJson.list(body, "blobs")) {
            String hash = String.valueOf(MiniJson.str(d, "hash"));
            if (blobs.containsKey(hash)) {
                skipped.add(d);
                continue;
            }
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/octet-stream");
            headers.put(
                    "x-amz-checksum-sha256",
                    Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash)));
            headers.put("content-length", String.valueOf(size(d)));
            requests.add(signed(hash, "PUT", headers));
        }
        json(ex, 200, Map.of("requests", requests, "skipped", skipped));
    }

    private void commit(HttpExchange ex, boolean writer, @Nullable Object body) throws IOException {
        if (!writer) {
            problem(ex, 403, "forbidden", "ci-fork callers write nothing", List.of());
            return;
        }
        List<Object> committed = new ArrayList<>();
        List<Object> absent = new ArrayList<>();
        for (Object d : MiniJson.list(body, "blobs")) {
            String hash = String.valueOf(MiniJson.str(d, "hash"));
            byte[] bytes = uploaded.remove(hash);
            if (bytes != null && bytes.length == size(d)) {
                blobs.put(hash, bytes);
                committed.add(d);
            } else absent.add(d);
        }
        json(ex, 200, Map.of("committed", committed, "absent", absent));
    }

    private void downloadUrls(HttpExchange ex, @Nullable Object body) throws IOException {
        List<Object> requests = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        for (Object d : MiniJson.list(body, "blobs")) {
            String hash = String.valueOf(MiniJson.str(d, "hash"));
            if (blobs.containsKey(hash)) requests.add(signed(hash, "GET", Map.of()));
            else skipped.add(d);
        }
        json(ex, 200, Map.of("requests", requests, "skipped", skipped));
    }

    private Map<String, Object> signed(String hash, String method, Map<String, Object> headers) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hash", hash);
        r.put("method", method);
        r.put(
                "url",
                "http://127.0.0.1:" + objects.getAddress().getPort() + "/o/org_fake/cas/sha256/" + hash
                        + "?X-Amz-Signature=fake");
        r.put("headers", headers);
        r.put("expires_at", "2099-01-01T00:00:00Z");
        return r;
    }

    private void lookup(HttpExchange ex, @Nullable Object body) throws IOException {
        lookups.add(body);
        if (stallMillis > 0) {
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> policies = new LinkedHashMap<>();
        for (Object entry : MiniJson.list(body, "accept")) {
            for (Object key : MiniJson.list(entry, "keys")) policies.put(String.valueOf(key), entry);
        }
        List<Object> found = new ArrayList<>();
        List<Object> missing = new ArrayList<>();
        List<Object> byReads = new ArrayList<>();
        for (Object k : MiniJson.list(body, "keys")) {
            String key = String.valueOf(k);
            Stored best = best(records.getOrDefault(key, List.of()), policies.get(key));
            if (best != null) found.add(found(key, best));
            else missing.add(key);
        }
        for (Object k : missing) {
            int taken = 0;
            for (Map.Entry<String, List<Stored>> e : records.entrySet()) {
                Stored best = best(e.getValue(), policies.get(String.valueOf(k)));
                if (best == null || best.reads() == null || !k.equals(MiniJson.str(best.reads(), "base"))) continue;
                if (taken++ == 4) break;
                byReads.add(found(e.getKey(), best));
            }
        }
        byReadsServed.addAndGet(byReads.size());
        json(ex, 200, Map.of("found", found, "missing", missing, "followed", List.of(), "by_reads", byReads));
    }

    /** The newest record of {@code under} whose blobs are all held and whose toolchain {@code policy} takes. */
    private @Nullable Stored best(List<Stored> under, @Nullable Object policy) {
        Stored best = null;
        synchronized (under) {
            for (Stored s : under) {
                if (!whole(s.text()) || !accepts(policy, s.toolchain())) continue;
                if (best == null || s.seq() > best.seq()) best = s;
            }
        }
        return best;
    }

    private boolean whole(String text) {
        Matcher m = OUTPUT.matcher(text);
        while (m.find()) {
            byte[] held = blobs.get(m.group(1));
            if (held == null || held.length != Long.parseLong(m.group(2))) return false;
        }
        return true;
    }

    /** The reader's policy as section 6.2 states it, for {@code toolchain:<kind>:<vendor>/<version>/…} tokens. */
    static boolean accepts(@Nullable Object policy, @Nullable String toolchain) {
        if (policy == null) return true;
        Object require = MiniJson.get(policy, "require");
        if (toolchain == null) return require == null;
        String mine = String.valueOf(MiniJson.str(policy, "toolchain"));
        String vendor = MiniJson.str(require, "vendor");
        if (vendor != null && !vendorOf(toolchain).contains(vendor.toLowerCase(Locale.ROOT))) return false;
        String version = MiniJson.str(require, "version");
        if (version != null && majorOf(version.replace(">=", "")) != majorOf(versionOf(toolchain))) return false;
        if (toolchain.equals(mine)) return true;
        int level = MiniJson.get(policy, "level") instanceof Number n ? n.intValue() : majorOf(versionOf(mine));
        int theirs = majorOf(versionOf(toolchain));
        if (theirs < level) return false;
        String match = MiniJson.str(policy, "match");
        return switch (match == null ? "major" : match) {
            case "level" -> true;
            case "build" -> false;
            default -> theirs == majorOf(versionOf(mine));
        };
    }

    private static String vendorOf(String token) {
        String[] parts =
                token.substring(token.indexOf(':', "toolchain:".length()) + 1).split("/", -1);
        return parts[0];
    }

    private static String versionOf(String token) {
        String[] parts =
                token.substring(token.indexOf(':', "toolchain:".length()) + 1).split("/", -1);
        return parts.length > 1 ? parts[1] : "";
    }

    private static int majorOf(String version) {
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i))) i++;
        return i == 0 ? -1 : Integer.parseInt(version.substring(0, i));
    }

    private static Map<String, Object> found(String key, Stored s) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("kind", "java:compile");
        f.put("writes", s.writes());
        f.put("scope", "trusted");
        f.put("record", s.text());
        f.put("host", "linux-x86_64");
        if (s.toolchain() != null) f.put("toolchain", s.toolchain());
        if (s.reads() != null) f.put("reads", s.reads());
        f.put("stored_by", "ci-protected:fake");
        f.put("stored_at", "2026-11-15T17:03:11Z");
        return f;
    }

    private void put(HttpExchange ex, boolean writer, String key, @Nullable Object body) throws IOException {
        if (!writer) {
            problem(ex, 403, "forbidden", "ci-fork callers write nothing", List.of());
            return;
        }
        String text = MiniJson.str(body, "record");
        if (text == null || !key.equals(header(text, "KEY"))) {
            problem(ex, 422, "invalid", "KEY does not name " + key, List.of());
            return;
        }
        if (text.indexOf('\u0000') >= 0) {
            // As the cache service does: a record is text, and its index stores no U+0000.
            problem(ex, 422, "invalid", "a record holds U+0000", List.of());
            return;
        }
        String writes = header(text, "WRITES");
        if (writes == null) {
            problem(ex, 422, "refused_kind", "a record of no write class", List.of());
            return;
        }
        Object reads = MiniJson.get(body, "reads");
        if (reads != null) {
            for (Object name : MiniJson.list(reads, "files")) {
                String n = String.valueOf(name);
                if (!PORTABLE.matcher(n).matches() || n.contains("/../") || n.contains(":../")) {
                    problem(ex, 422, "invalid", "read list names a checkout path: " + n, List.of());
                    return;
                }
            }
        }
        List<Object> lacking = new ArrayList<>();
        Matcher m = OUTPUT.matcher(text);
        while (m.find()) {
            if (!blobs.containsKey(m.group(1)))
                lacking.add(Map.of("hash", m.group(1), "size", Long.parseLong(m.group(2))));
        }
        if (!lacking.isEmpty()) {
            problem(ex, 412, "blobs_missing", lacking.size() + " blobs the record names are not uploaded", lacking);
            return;
        }
        String toolchain = header(text, "TOOLCHAIN");
        List<Stored> under = records.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        String outcome = "stored";
        synchronized (under) {
            for (Stored s : List.copyOf(under)) {
                if (!Objects.equals(s.toolchain(), toolchain)) continue;
                if (s.text().equals(text)) outcome = "unchanged";
                else if (writes.equals("test-marker") || writes.equals("class-ledger")) {
                    under.remove(s);
                    outcome = "replaced";
                } else {
                    problem(ex, 409, "conflict", "another record is held under " + key, List.of());
                    return;
                }
            }
            if (!outcome.equals("unchanged"))
                under.add(new Stored(text, writes, toolchain, reads, seq.incrementAndGet()));
        }
        json(ex, 200, Map.of("key", key, "scope", "trusted", "outcome", outcome));
    }

    // ---- the object store --------------------------------------------------------------------

    private void serveObject(HttpExchange ex) throws IOException {
        try (ex) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            storeAuthorizations.add(auth == null ? "-" : auth);
            String path = ex.getRequestURI().getPath();
            String hash = path.substring(path.lastIndexOf('/') + 1);
            if (ex.getRequestMethod().equals("PUT")) {
                byte[] bytes;
                try (InputStream in = ex.getRequestBody()) {
                    bytes = in.readAllBytes();
                }
                String checksum = ex.getRequestHeaders().getFirst("x-amz-checksum-sha256");
                if (checksum == null
                        || !checksum.equals(Base64.getEncoder()
                                .encodeToString(HexFormat.of().parseHex(sha256(bytes))))) {
                    send(ex, 400, new byte[0]);
                    return;
                }
                uploaded.put(hash, bytes);
                send(ex, 200, new byte[0]);
            } else {
                byte[] bytes = served(hash);
                if (bytes == null) send(ex, 404, new byte[0]);
                else send(ex, 200, bytes);
            }
        }
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private byte @Nullable [] served(String hash) {
        byte[] bytes = blobs.get(hash);
        if (bytes == null || !tampered.contains(hash)) return bytes;
        byte[] altered = bytes.clone();
        if (altered.length > 0) altered[0] ^= 1;
        return altered;
    }

    private static @Nullable String header(String text, String field) {
        for (String line : text.split("\n")) {
            if (line.startsWith(field + " "))
                return line.substring(field.length() + 1).strip();
        }
        return null;
    }

    private static long size(@Nullable Object digest) {
        return MiniJson.get(digest, "size") instanceof Number n ? n.longValue() : -1;
    }

    private static @Nullable Object readJson(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readAllBytes();
        }
        return bytes.length == 0 ? null : MiniJson.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, status, MiniJson.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void problem(HttpExchange ex, int status, String code, String detail, List<Object> missing)
            throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "https://jumpkick.build/dojo/problems/" + code);
        p.put("code", code);
        p.put("status", status);
        p.put("detail", detail);
        p.put("retryable", false);
        p.put("missing", missing);
        ex.getResponseHeaders().set("Content-Type", "application/problem+json");
        send(ex, status, MiniJson.write(p).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** {@code bytes}' sha256 as 64 lower-case hex digits. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
