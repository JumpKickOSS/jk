// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk audit} orchestration: lockfile → OSV batch query → concurrent detail fetch for hits.
 * Findings emit in deterministic package × result order.
 */
public final class OsvAuditor {

    private final OsvClient client;

    public OsvAuditor() {
        this(new OsvClient());
    }

    public OsvAuditor(OsvClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AuditReport audit(Lockfile lock) throws IOException, InterruptedException {
        List<OsvClient.Query> queries = new ArrayList<>();
        List<Lockfile.Artifact> pkgs = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Lockfile.Artifact.name is the four-part coordinate ("group:artifact:jar:"); OSV keys
            // Maven packages on "group:artifact". Sending the four-part form matched nothing, so
            // every audit reported clean. PackageId.ga() is the one owner of that projection.
            queries.add(new OsvClient.Query("Maven", PackageId.parse(pkg.name()).ga(), pkg.version()));
            pkgs.add(pkg);
        }
        if (queries.isEmpty()) return new AuditReport(List.of());

        List<OsvClient.Result> results = client.queryBatch(queries);

        // Collect every unique vuln ID surfaced by the batch — a single CVE
        // can apply to multiple packages, and we don't want to fetch its
        // details N times.
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (OsvClient.Result r : results) uniqueIds.addAll(r.vulnIds());
        if (uniqueIds.isEmpty()) return new AuditReport(List.of());

        // Dispatch all detail fetches concurrently.
        Map<String, CompletableFuture<OsvClient.Vulnerability>> futures = uniqueIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return client.fetchVuln(id);
                                    } catch (IOException | InterruptedException e) {
                                        if (e instanceof InterruptedException)
                                            Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    }
                                },
                                JkThreads.io())));

        // Assemble findings in the same package × vulnId order as before —
        // determinism is important for the supply-chain report sidecar.
        List<AuditReport.Finding> findings = new ArrayList<>();
        try {
            for (int i = 0; i < pkgs.size(); i++) {
                Lockfile.Artifact pkg = pkgs.get(i);
                for (String vulnId : results.get(i).vulnIds()) {
                    OsvClient.Vulnerability v = futures.get(vulnId).get();
                    String ga = PackageId.parse(pkg.name()).ga();
                    findings.add(new AuditReport.Finding(
                            ga,
                            pkg.version(),
                            vulnId,
                            v.summary(),
                            AuditReport.Severity.parse(v.severity()),
                            fixedIn(v.fixedVersions(ga), pkg.version())));
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re && re.getCause() instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re && re.getCause() instanceof InterruptedException ie) throw ie;
            throw new IOException("audit vuln-detail fetch failed: " + cause.getMessage(), cause);
        }
        return new AuditReport(findings);
    }

    /**
     * The lowest of {@code fixed} above {@code locked} — the nearest upgrade that closes the
     * advisory — or {@code null} when OSV names none above it.
     */
    static @Nullable String fixedIn(List<String> fixed, String locked) {
        String best = null;
        for (String candidate : fixed) {
            if (Versions.compare(candidate, locked) <= 0) continue;
            if (best == null || Versions.compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }
}
