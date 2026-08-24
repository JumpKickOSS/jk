// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
    // Jackson 3 (tools.jackson.*) is the version we depend on; ObjectMapper from
    // tools.jackson.databind has the same API surface as Jackson 2 for our needs.
    private final JsonMapper json = JsonMapper.builder().build();

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

    public record Vulnerability(String id, String summary, String severity, String details) {}

    /** Batch query — one Result per input query, in the same order. */
    public List<Result> queryBatch(List<Query> queries) throws IOException, InterruptedException {
        if (queries.isEmpty()) return List.of();
        // Hand-roll the request body — Jackson's tree API is overkill here and we
        // avoid pulling another type onto the API surface.
        StringBuilder body = new StringBuilder();
        body.append("{\"queries\":[");
        for (int i = 0; i < queries.size(); i++) {
            if (i > 0) body.append(',');
            Query q = queries.get(i);
            body.append("{\"package\":{")
                    .append("\"ecosystem\":")
                    .append(quote(q.ecosystem()))
                    .append(',')
                    .append("\"name\":")
                    .append(quote(q.name()))
                    .append("},\"version\":")
                    .append(quote(q.version()))
                    .append('}');
        }
        body.append("]}");

        HttpRequest request = HttpRequest.newBuilder(batchUrl)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
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
            var node = json.readTree(response.body());
            String summary = textOrEmpty(node, "summary");
            String details = textOrEmpty(node, "details");
            String severity = extractSeverity(node);
            return new Vulnerability(vulnId, summary, severity, details);
        } catch (Exception e) {
            throw new IOException("failed to parse OSV vuln body for " + vulnId, e);
        }
    }

    // --- helpers ---------------------------------------------------------

    private List<Result> parseBatchResponse(String body, int expectedSize) throws IOException {
        try {
            var root = json.readTree(body);
            var resultsNode = root.get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                throw new IOException("OSV batch response missing `results` array");
            }
            List<Result> out = new ArrayList<>(resultsNode.size());
            for (var entry : resultsNode) {
                var vulnsNode = entry.get("vulns");
                List<String> ids = new ArrayList<>();
                if (vulnsNode != null && vulnsNode.isArray()) {
                    for (var v : vulnsNode) {
                        var id = v.get("id");
                        if (id != null && id.isString()) ids.add(id.stringValue());
                    }
                }
                out.add(new Result(ids));
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
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to parse OSV batch response", e);
        }
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
    private static String extractSeverity(JsonNode node) {
        var dbSpecific = node.get("database_specific");
        if (dbSpecific != null) {
            var inner = dbSpecific.get("severity");
            if (inner != null && inner.isString()) return inner.stringValue();
        }
        return "UNKNOWN";
    }

    private static String textOrEmpty(JsonNode node, String field) {
        var v = node.get(field);
        return v == null || !v.isString() ? "" : v.stringValue();
    }

    private static String quote(String s) {
        return Jsonl.quote(s);
    }
}
