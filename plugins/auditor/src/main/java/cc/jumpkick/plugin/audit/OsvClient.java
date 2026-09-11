// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Client for the <a href="https://api.osv.dev/v1/querybatch">OSV v1 batch query API</a>. One
 * round trip per lockfile; full vuln details are fetched separately when needed.
 */
public final class OsvClient {

    public static final URI DEFAULT_BATCH = URI.create("https://api.osv.dev/v1/querybatch");
    public static final URI DEFAULT_VULNS = URI.create("https://api.osv.dev/v1/vulns/");

    /** GHSA-…, CVE-…, GO-… and friends: letters, digits, dot, dash, underscore. Nothing path-like. */
    private static final Pattern VULN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final HttpClient http;
    private final URI batchUrl;
    private final URI vulnsUrl;

    public OsvClient() {
        this(DEFAULT_BATCH, DEFAULT_VULNS);
    }

    public OsvClient(URI batchUrl, URI vulnsUrl) {
        this.batchUrl = Objects.requireNonNull(batchUrl, "batchUrl");
        this.vulnsUrl = Objects.requireNonNull(vulnsUrl, "vulnsUrl");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Query(String ecosystem, String name, String version) {}

    public record Result(List<String> vulnIds) {
        public Result {
            vulnIds = List.copyOf(vulnIds);
        }

        public boolean clean() {
            return vulnIds.isEmpty();
        }
    }

    /**
     * One advisory. {@code fixedVersions} maps each affected Maven package ({@code group:artifact})
     * to the versions its ranges name as {@code fixed}, in feed order; a package with no fix, or an
     * advisory with no Maven ranges, is simply absent.
     */
    public record Vulnerability(
            String id, String summary, String severity, String details, Map<String, List<String>> fixedVersions) {
        public Vulnerability {
            fixedVersions = Map.copyOf(fixedVersions);
        }

        /** The fixed versions OSV lists for {@code mavenPackage}; empty when it names none. */
        public List<String> fixedVersions(String mavenPackage) {
            return fixedVersions.getOrDefault(mavenPackage, List.of());
        }
    }

    /** Batch query — one Result per input query, in the same order. */
    public List<Result> queryBatch(List<Query> queries) throws IOException, InterruptedException {
        if (queries.isEmpty()) return List.of();
        List<Object> rows = new ArrayList<>(queries.size());
        for (Query q : queries) {
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("ecosystem", q.ecosystem());
            pkg.put("name", q.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("package", pkg);
            row.put("version", q.version());
            rows.add(row);
        }
        String body = MiniJson.write(Map.of("queries", rows));

        HttpRequest request = HttpRequest.newBuilder(batchUrl)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV batch query failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        return parseBatchResponse(response.body(), queries.size());
    }

    /** Fetch full vulnerability metadata. */
    public Vulnerability fetchVuln(String vulnId) throws IOException, InterruptedException {
        // vulnId comes from the batch response, so it is remote input. `URI.resolve` on
        // "//evil.example/x" or "../" retargets the host or escapes the path, so the id is
        // validated as a bare advisory identifier before it is ever resolved.
        if (!VULN_ID.matcher(vulnId).matches()) {
            throw new IOException("refusing to fetch a malformed OSV vulnerability id: " + vulnId);
        }
        var url = vulnsUrl.resolve(vulnId);
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV vuln fetch failed for " + vulnId + ": HTTP " + response.statusCode());
        }
        try {
            Map<?, ?> node = object(MiniJson.parse(response.body()));
            return new Vulnerability(
                    vulnId,
                    textOrEmpty(node, "summary"),
                    extractSeverity(node),
                    textOrEmpty(node, "details"),
                    extractFixedVersions(node));
        } catch (RuntimeException e) {
            throw new IOException("failed to parse OSV vuln body for " + vulnId, e);
        }
    }

    // --- helpers ---------------------------------------------------------

    private static List<Result> parseBatchResponse(String body, int expectedSize) throws IOException {
        List<Result> out = new ArrayList<>(expectedSize);
        try {
            Object results = object(MiniJson.parse(body)).get("results");
            if (!(results instanceof List<?> rows)) {
                throw new IOException("OSV batch response missing `results` array");
            }
            for (Object entry : rows) {
                List<String> ids = new ArrayList<>();
                if (object(entry).get("vulns") instanceof List<?> vulns) {
                    for (Object v : vulns) {
                        if (object(v).get("id") instanceof String id) ids.add(id);
                    }
                }
                out.add(new Result(ids));
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
     * The advisory's severity <em>label</em>.
     *
     * <p>{@code severity[].score} is a CVSS <em>vector</em> ({@code CVSS:3.1/AV:N/...}), not a label,
     * so it is never returned as one — reading it was why every advisory classified as
     * {@code UNKNOWN}. {@code database_specific.severity} is the label OSV and GitHub actually
     * publish. An advisory with only a vector stays unclassified and gates anyway, because
     * {@code Severity.UNKNOWN} fails closed.
     */
    private static String extractSeverity(Map<?, ?> node) {
        Object label = object(node.get("database_specific")).get("severity");
        return label instanceof String s ? s : "UNKNOWN";
    }

    /**
     * {@code affected[].ranges[].events[].fixed}, per Maven package. OSV states a fix as an event in
     * a version range rather than as a field on the advisory, and an advisory usually spans several
     * release lines, so one package may carry several fixed versions.
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
