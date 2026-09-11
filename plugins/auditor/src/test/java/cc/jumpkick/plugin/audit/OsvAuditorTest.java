// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OsvAuditorTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> get = new HashMap<>();
    private final Map<String, byte[]> post = new HashMap<>();
    private final List<String> postBodies = new ArrayList<>();
    private final List<String> getPaths = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if ("GET".equals(exchange.getRequestMethod())) {
                getPaths.add(path);
                body = get.get(path);
            } else {
                postBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                body = post.get(path);
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * Lockfile coordinates are four-part ({@code group:artifact:jar:}); OSV keys Maven packages on
     * {@code group:artifact}. Sending the four-part form matched nothing, so every audit reported
     * clean. Fixtures use the real lockfile shape so that cannot recur silently.
     */
    @Test
    void osv_is_queried_with_the_two_part_coordinate_not_the_lockfile_name() throws Exception {
        post.put("/v1/querybatch", "{\"results\":[{}]}".getBytes(StandardCharsets.UTF_8));

        new OsvAuditor(osvClient()).audit(lockOf(artifact("com.android.tools.build:apksig:jar:", "9.3.1")));

        assertThat(postBodies).hasSize(1);
        assertThat(postBodies.getFirst())
                .as("OSV must receive group:artifact")
                .contains("\"name\":\"com.android.tools.build:apksig\"")
                .doesNotContain("apksig:jar:");
    }

    @Test
    void empty_lockfile_yields_empty_report() throws Exception {
        AuditReport report = new OsvAuditor(osvClient()).audit(Lockfile.empty("test"));
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void osv_findings_become_severity_classified_report() throws Exception {
        Lockfile lock = lockOf(
                artifact("com.fasterxml.jackson.core:jackson-databind:jar:", "2.18.0"),
                artifact("com.example:safe:jar:", "1.0.0"));

        post.put("/v1/querybatch", """
                {"results":[
                  {"vulns":[{"id":"GHSA-aaaa-bbbb-cccc"}]},
                  {}
                ]}
                """.getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/GHSA-aaaa-bbbb-cccc", """
                {
                  "id":"GHSA-aaaa-bbbb-cccc",
                  "summary":"Deserialization gadget",
                  "database_specific":{"severity":"HIGH"}
                }
                """.getBytes(StandardCharsets.UTF_8));

        AuditReport report = new OsvAuditor(osvClient()).audit(lock);
        assertThat(report.findings()).hasSize(1);
        AuditReport.Finding f = report.findings().getFirst();
        assertThat(f.module()).isEqualTo("com.fasterxml.jackson.core:jackson-databind");
        assertThat(f.version()).isEqualTo("2.18.0");
        assertThat(f.vulnId()).isEqualTo("GHSA-aaaa-bbbb-cccc");
        assertThat(f.severity()).isEqualTo(AuditReport.Severity.HIGH);
        assertThat(f.summary()).contains("Deserialization");
        assertThat(report.renderMarkdown()).contains("**HIGH**").contains("GHSA-aaaa-bbbb-cccc");
    }

    /** GitHub publishes MODERATE where OSV's own scale says MEDIUM. It must not fall through. */
    @Test
    void moderate_is_the_github_spelling_of_medium() throws Exception {
        AuditReport report = auditOne("""
                {"id":"GHSA-mod","summary":"m","database_specific":{"severity":"MODERATE"}}
                """);
        assertThat(report.findings().getFirst().severity()).isEqualTo(AuditReport.Severity.MEDIUM);
    }

    /**
     * A CVSS vector is not a label. Reading {@code severity[].score} as one classified every
     * advisory as UNKNOWN; and because UNKNOWN did not satisfy any threshold, nothing gated. An
     * unclassifiable advisory must still be reported at every threshold.
     */
    @Test
    void a_cvss_vector_only_advisory_is_unknown_and_still_gates() throws Exception {
        AuditReport report = auditOne("""
                {
                  "id":"CVE-2025-0001",
                  "summary":"vector only",
                  "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}]
                }
                """);
        AuditReport.Finding f = report.findings().getFirst();
        assertThat(f.severity()).isEqualTo(AuditReport.Severity.UNKNOWN);
        assertThat(report.filterAtLeast(AuditReport.Severity.CRITICAL))
                .as("an unclassified advisory fails closed")
                .extracting(AuditReport.Finding::vulnId)
                .containsExactly("CVE-2025-0001");
    }

    /** The vuln id is remote input; URI.resolve on it would otherwise retarget the host. */
    @Test
    void a_response_supplied_id_cannot_retarget_the_host() throws Exception {
        post.put("/v1/querybatch", """
                {"results":[{"vulns":[{"id":"//evil.example/x"}]}]}
                """.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new OsvAuditor(osvClient()).audit(lockOf(artifact("g:a:jar:", "1.0"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("malformed OSV vulnerability id");

        assertThat(getPaths).as("no detail fetch may be attempted at all").isEmpty();
    }

    @Test
    void severity_threshold_filter_blocks_only_at_or_above() throws Exception {
        Lockfile lock = lockOf(artifact("g:a:jar:", "1.0"), artifact("g:b:jar:", "1.0"));
        post.put("/v1/querybatch", """
                {"results":[
                  {"vulns":[{"id":"A"}]},
                  {"vulns":[{"id":"B"}]}
                ]}
                """.getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/A", """
                {"id":"A","summary":"low","database_specific":{"severity":"LOW"}}
                """.getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/B", """
                {"id":"B","summary":"high","database_specific":{"severity":"HIGH"}}
                """.getBytes(StandardCharsets.UTF_8));

        AuditReport report = new OsvAuditor(osvClient()).audit(lock);
        assertThat(report.filterAtLeast(AuditReport.Severity.HIGH))
                .extracting(AuditReport.Finding::vulnId)
                .containsExactly("B");
        assertThat(report.filterAtLeast(AuditReport.Severity.LOW))
                .extracting(AuditReport.Finding::vulnId)
                .containsExactlyInAnyOrder("A", "B");
    }

    /**
     * OSV states a fix as a {@code fixed} event inside a version range, one range per release line.
     * The finding names the nearest fixed version above the locked one — the upgrade that closes
     * the advisory — not the first line's fix, which may be below the locked version already.
     */
    @Test
    void fixed_in_is_the_nearest_fixed_version_above_the_locked_one() throws Exception {
        Lockfile lock = lockOf(artifact("com.fasterxml.jackson.core:jackson-databind:jar:", "2.10.0"));
        post.put("/v1/querybatch", """
                {"results":[{"vulns":[{"id":"GHSA-fix"}]}]}
                """.getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/GHSA-fix", """
                {
                  "id":"GHSA-fix",
                  "summary":"gadget",
                  "database_specific":{"severity":"HIGH"},
                  "affected":[
                    {"package":{"ecosystem":"Maven","name":"com.fasterxml.jackson.core:jackson-databind"},
                     "ranges":[
                       {"type":"ECOSYSTEM","events":[{"introduced":"2.9.0"},{"fixed":"2.9.10.4"}]},
                       {"type":"ECOSYSTEM","events":[{"introduced":"2.10.0"},{"fixed":"2.10.0.1"}]},
                       {"type":"ECOSYSTEM","events":[{"introduced":"2.11.0"},{"fixed":"2.11.1"}]}
                     ]},
                    {"package":{"ecosystem":"npm","name":"jackson-databind"},
                     "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"9.9.9"}]}]}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8));

        AuditReport report = new OsvAuditor(osvClient()).audit(lock);
        assertThat(report.findings().getFirst().fixedIn()).isEqualTo("2.10.0.1");
    }

    /** An advisory that names no fix above the locked version reports none rather than inventing one. */
    @Test
    void fixed_in_is_absent_when_osv_names_no_fix_above_the_locked_version() throws Exception {
        assertThat(OsvAuditor.fixedIn(List.of(), "1.0")).isNull();
        assertThat(OsvAuditor.fixedIn(List.of("0.9", "1.0"), "1.0")).isNull();
        assertThat(OsvAuditor.fixedIn(List.of("1.2", "1.0.1", "2.0"), "1.0")).isEqualTo("1.0.1");
        AuditReport report = auditOne("""
                {"id":"GHSA-nofix","summary":"m","database_specific":{"severity":"LOW"}}
                """);
        assertThat(report.findings().getFirst().fixedIn()).isNull();
    }

    private AuditReport auditOne(String vulnJson) throws Exception {
        String id = vulnJson.split("\"id\":\"")[1].split("\"")[0];
        post.put(
                "/v1/querybatch",
                ("{\"results\":[{\"vulns\":[{\"id\":\"" + id + "\"}]}]}").getBytes(StandardCharsets.UTF_8));
        get.put("/v1/vulns/" + id, vulnJson.getBytes(StandardCharsets.UTF_8));
        return new OsvAuditor(osvClient()).audit(lockOf(artifact("g:a:jar:", "1.0")));
    }

    private static Lockfile.Artifact artifact(String name, String version) {
        return new Lockfile.Artifact(
                name, version, "central+https://...", "sha256:abc", null, List.of(Scope.MAIN), List.of());
    }

    private static Lockfile lockOf(Lockfile.Artifact... artifacts) {
        return new Lockfile(5, "jk test", "pubgrub-v1", List.of(artifacts));
    }

    private OsvClient osvClient() {
        return new OsvClient(base.resolve("/v1/querybatch"), base.resolve("/v1/vulns/"));
    }
}
