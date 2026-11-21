// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Client for the <a href="https://api.osv.dev/v1/querybatch">OSV v1 batch query API</a>. One
 * round trip per thousand locked artifacts, plus one per page when OSV paginates a large hit set;
 * full vuln details are fetched separately when needed.
 *
 * <p>Requests go through {@link Http}, so the proxy jk is configured with — {@code [network]} or
 * the shell's proxy variables, handed to this worker over the engine's own — carries the audit
 * exactly as it carries a resolve; {@code --offline} is refused there too.
 */
public final class OsvClient {

    public static final URI DEFAULT_BATCH = URI.create("https://api.osv.dev/v1/querybatch");
    public static final URI DEFAULT_VULNS = URI.create("https://api.osv.dev/v1/vulns/");

    /** GHSA-…, CVE-…, GO-… and friends: letters, digits, dot, dash, underscore. Nothing path-like. */
    private static final Pattern VULN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Batch pages followed per audit; a token still present afterwards fails the audit. */
    static final int MAX_PAGES = 50;

    /** Queries one querybatch request may carry; OSV refuses a larger batch. */
    static final int MAX_BATCH = 1_000;

    /** Range types whose events are versions; a {@code GIT} range's events are commit hashes. */
    private static final Set<String> VERSION_RANGES = Set.of("ECOSYSTEM", "SEMVER");

    private final Http http;
    private final URI batchUrl;
    private final URI vulnsUrl;
    private final @Nullable Path cacheDir;

    public OsvClient() {
        this(DEFAULT_BATCH, DEFAULT_VULNS);
    }

    public OsvClient(URI batchUrl, URI vulnsUrl) {
        this(batchUrl, vulnsUrl, new Http());
    }

    /** Every collaborator explicit — a test pairs a stub endpoint with a client it can watch. */
    public OsvClient(URI batchUrl, URI vulnsUrl, Http http) {
        this(batchUrl, vulnsUrl, http, null);
    }

    /**
     * As {@link #OsvClient(URI, URI, Http)}, keeping each advisory's body under {@code cacheDir}
     * (none when null) so an audit fetches only the advisories OSV has modified since.
     */
    public OsvClient(URI batchUrl, URI vulnsUrl, Http http, @Nullable Path cacheDir) {
        this.batchUrl = Objects.requireNonNull(batchUrl, "batchUrl");
        this.vulnsUrl = directory(Objects.requireNonNull(vulnsUrl, "vulnsUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.cacheDir = cacheDir;
    }

    /**
     * {@code uri} with a trailing slash on its path, so resolving an id against it appends a
     * segment instead of replacing the last one.
     */
    private static URI directory(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.endsWith("/")) return uri;
        return uri.resolve(path + "/");
    }

    public record Query(String ecosystem, String name, String version) {}

    /**
     * One query's advisories. {@code modified} maps an id to the time OSV last changed that
     * advisory, as the batch reported it; an id OSV gave no time for is absent.
     */
    public record Result(List<String> vulnIds, Map<String, String> modified) {
        public Result {
            vulnIds = List.copyOf(vulnIds);
            modified = Map.copyOf(modified);
        }

        public boolean clean() {
            return vulnIds.isEmpty();
        }
    }

    /**
     * One advisory. {@code fixedVersions} maps each affected Maven package ({@code group:artifact})
     * to the versions its ranges name as {@code fixed}, in feed order; a package with no fix, or an
     * advisory with no Maven ranges, is simply absent. {@code aliases} are its other ids.
     */
    public record Vulnerability(
            String id,
            String summary,
            String severity,
            String details,
            Map<String, List<String>> fixedVersions,
            List<String> aliases) {
        public Vulnerability {
            fixedVersions = Map.copyOf(fixedVersions);
            aliases = List.copyOf(aliases);
        }

        /** The fixed versions OSV lists for {@code mavenPackage}; empty when it names none. */
        public List<String> fixedVersions(String mavenPackage) {
            return fixedVersions.getOrDefault(mavenPackage, List.of());
        }
    }

    /**
     * Batch query — one Result per input query, in the same order. A result that carries a
     * {@code next_page_token} is re-queried with that token until none remains, so a query with
     * more hits than one page holds still reports every id; ids stay de-duplicated per query in
     * first-seen order. A token still present after {@link #MAX_PAGES} pages fails the audit.
     */
    public List<Result> queryBatch(List<Query> queries) throws IOException, InterruptedException {
        if (queries.isEmpty()) return List.of();
        List<LinkedHashSet<String>> ids = new ArrayList<>(queries.size());
        List<Map<String, String>> modified = new ArrayList<>(queries.size());
        Map<Integer, @Nullable String> pending = new LinkedHashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            ids.add(new LinkedHashSet<>());
            modified.add(new LinkedHashMap<>());
            pending.put(i, null);
        }
        for (int page = 0; !pending.isEmpty(); page++) {
            if (page == MAX_PAGES) {
                throw new IOException("OSV batch query did not finish: "
                        + pending.size()
                        + " quer"
                        + (pending.size() == 1 ? "y" : "ies")
                        + " still had more results after "
                        + MAX_PAGES
                        + " pages");
            }
            Map<Integer, @Nullable String> next = new LinkedHashMap<>();
            List<Map.Entry<Integer, @Nullable String>> all = new ArrayList<>(pending.entrySet());
            for (int from = 0; from < all.size(); from += MAX_BATCH) {
                List<Map.Entry<Integer, @Nullable String>> batch =
                        all.subList(from, Math.min(all.size(), from + MAX_BATCH));
                List<Object> rows = new ArrayList<>(batch.size());
                for (var entry : batch) rows.add(row(queries.get(entry.getKey()), entry.getValue()));
                List<Page> pages = post(rows);
                for (int p = 0; p < batch.size(); p++) {
                    int index = batch.get(p).getKey();
                    Page result = pages.get(p);
                    ids.get(index).addAll(result.vulnIds());
                    modified.get(index).putAll(result.modified());
                    if (result.nextPageToken() != null) next.put(index, result.nextPageToken());
                }
            }
            pending = next;
        }
        List<Result> out = new ArrayList<>(queries.size());
        for (int i = 0; i < ids.size(); i++) out.add(new Result(List.copyOf(ids.get(i)), modified.get(i)));
        return out;
    }

    /** One querybatch request of {@code rows}, at most {@link #MAX_BATCH}; one page per row, in order. */
    private List<Page> post(List<Object> rows) throws IOException, InterruptedException {
        String body = MiniJson.write(Map.of("queries", rows));
        HttpResponse<byte[]> response = http.documents()
                .post(batchUrl, body.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV batch query failed: HTTP " + response.statusCode() + ": " + text(response));
        }
        return parseBatchResponse(text(response), rows.size());
    }

    /** One querybatch row; {@code pageToken} continues an earlier page of the same query. */
    private static Map<String, Object> row(Query q, @Nullable String pageToken) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("ecosystem", q.ecosystem());
        pkg.put("name", q.name());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("package", pkg);
        row.put("version", q.version());
        if (pageToken != null) row.put("page_token", pageToken);
        return row;
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * Fetch full vulnerability metadata, from the cache when it holds the body OSV served at
     * {@code modified} (the batch's time for this advisory; null skips the cache).
     */
    public Vulnerability fetchVuln(String vulnId, @Nullable String modified) throws IOException, InterruptedException {
        // vulnId comes from the batch response, so it is remote input. `URI.resolve` on
        // "//evil.example/x" or "../" retargets the host or escapes the path, so the id is
        // validated as a bare advisory identifier before it is ever resolved, or used as a file name.
        if (!VULN_ID.matcher(vulnId).matches()) {
            throw new IOException("refusing to fetch a malformed OSV vulnerability id: " + vulnId);
        }
        VulnCache cached = cacheDir == null || modified == null || modified.isBlank()
                ? null
                : new VulnCache(cacheDir.resolve(vulnId + ".json"), modified);
        if (cached != null) {
            String body = cached.read();
            if (body != null) {
                try {
                    return parseVuln(vulnId, body);
                } catch (RuntimeException unreadable) {
                    // fetched again below, and the entry rewritten
                }
            }
        }
        var url = vulnsUrl.resolve(vulnId);
        HttpResponse<byte[]> response = http.documents().get(url);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV vuln fetch failed for " + vulnId + ": HTTP " + response.statusCode());
        }
        String body = text(response);
        Vulnerability vuln;
        try {
            vuln = parseVuln(vulnId, body);
        } catch (RuntimeException e) {
            throw new IOException("failed to parse OSV vuln body for " + vulnId, e);
        }
        if (cached != null) cached.write(body);
        return vuln;
    }

    private static Vulnerability parseVuln(String vulnId, String body) {
        Map<?, ?> node = object(MiniJson.parse(body));
        return new Vulnerability(
                vulnId,
                textOrEmpty(node, "summary"),
                extractSeverity(node),
                textOrEmpty(node, "details"),
                extractFixedVersions(node),
                strings(node.get("aliases")));
    }

    // --- helpers ---------------------------------------------------------

    /** One page of one query's hits; {@code nextPageToken} is null when OSV has no more. */
    private record Page(
            List<String> vulnIds,
            Map<String, String> modified,
            @Nullable String nextPageToken) {}

    private static List<Page> parseBatchResponse(String body, int expectedSize) throws IOException {
        List<Page> out = new ArrayList<>(expectedSize);
        try {
            Object results = object(MiniJson.parse(body)).get("results");
            if (!(results instanceof List<?> rows)) {
                throw new IOException("OSV batch response missing `results` array");
            }
            for (Object entry : rows) {
                List<String> ids = new ArrayList<>();
                Map<String, String> modified = new LinkedHashMap<>();
                if (object(entry).get("vulns") instanceof List<?> vulns) {
                    for (Object v : vulns) {
                        if (!(object(v).get("id") instanceof String id)) continue;
                        ids.add(id);
                        String stamp = textOrEmpty(object(v), "modified");
                        if (!stamp.isEmpty()) modified.put(id, stamp);
                    }
                }
                String token = textOrEmpty(object(entry), "next_page_token");
                out.add(new Page(ids, modified, token.isEmpty() ? null : token));
            }
        } catch (RuntimeException e) {
            throw new IOException("failed to parse OSV batch response", e);
        }
        if (out.size() != expectedSize) {
            throw new IOException("OSV returned "
                    + out.size()
                    + " result(s) for "
                    + expectedSize
                    + " quer"
                    + (expectedSize == 1 ? "y" : "ies"));
        }
        return out;
    }

    /**
     * The advisory's severity <em>label</em>: {@code database_specific.severity}, the label OSV and
     * GitHub publish; failing that, the rating of the highest score among its CVSS v3 and v4 vectors
     * ({@code severity[].score}, {@code CVSS:3.1/AV:N/...} or {@code CVSS:4.0/AV:N/...}), which is
     * never a label itself. An advisory with neither stays {@code UNKNOWN}, which fails closed.
     */
    private static String extractSeverity(Map<?, ?> node) {
        Object label = object(node.get("database_specific")).get("severity");
        if (label instanceof String s && !s.isBlank()) return s;
        Double best = null;
        if (node.get("severity") instanceof List<?> scores) {
            for (Object entry : scores) {
                String type = textOrEmpty(object(entry), "type");
                if (!type.equals("CVSS_V3") && !type.equals("CVSS_V4")) continue;
                Double score = Cvss.baseScore(textOrEmpty(object(entry), "score"));
                if (score != null && (best == null || score > best)) best = score;
            }
        }
        return best == null ? "UNKNOWN" : Cvss.label(best);
    }

    /** {@code value}'s string elements when it is a JSON array; empty otherwise. */
    private static List<String> strings(@Nullable Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    /**
     * {@code affected[].ranges[].events[].fixed}, per Maven package, from {@code ECOSYSTEM} and
     * {@code SEMVER} ranges only. OSV states a fix as an event in a version range rather than as a
     * field on the advisory, and an advisory usually spans several release lines, so one package may
     * carry several fixed versions.
     */
    private static Map<String, List<String>> extractFixedVersions(Map<?, ?> node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(node.get("affected") instanceof List<?> affected)) return out;
        for (Object entry : affected) {
            Map<?, ?> pkg = object(object(entry).get("package"));
            if (!"Maven".equalsIgnoreCase(textOrEmpty(pkg, "ecosystem"))) continue;
            String name = textOrEmpty(pkg, "name");
            if (name.isEmpty() || !(object(entry).get("ranges") instanceof List<?> ranges)) continue;
            for (Object range : ranges) {
                if (!VERSION_RANGES.contains(textOrEmpty(object(range), "type"))) continue;
                if (!(object(range).get("events") instanceof List<?> events)) continue;
                for (Object event : events) {
                    String fixed = textOrEmpty(object(event), "fixed");
                    if (!fixed.isEmpty())
                        out.computeIfAbsent(name, k -> new ArrayList<>()).add(fixed);
                }
            }
        }
        return out;
    }

    private static String textOrEmpty(Map<?, ?> node, String field) {
        return node.get(field) instanceof String s ? s : "";
    }

    /**
     * {@code value} as a JSON object, or an empty one when it is anything else. OSV is a remote
     * peer: a field that should hold an object can arrive as {@code null}, a string, or be absent,
     * and every one of those means "nothing to read here" rather than a crash.
     */
    private static Map<?, ?> object(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }
}
