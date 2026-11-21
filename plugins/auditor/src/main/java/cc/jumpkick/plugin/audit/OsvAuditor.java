// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.version.Versions;
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
        // One query per group:artifact and version: classifier jars of one version (a native jar
        // per platform) are one package to OSV, and a finding names no classifier.
        Set<Locked> unique = new LinkedHashSet<>();
        for (Lockfile.Artifact artifact : lock.artifacts()) {
            unique.add(new Locked(PackageId.parse(artifact.name()).ga(), artifact.version()));
        }
        List<Locked> pkgs = List.copyOf(unique);
        List<OsvClient.Query> queries = new ArrayList<>(pkgs.size());
        for (Locked pkg : pkgs) queries.add(new OsvClient.Query("Maven", pkg.ga(), pkg.version()));
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

        // Findings in package × vulnId order; the supply-chain report sidecar depends on it.
        List<AuditReport.Finding> findings = new ArrayList<>();
        try {
            for (int i = 0; i < pkgs.size(); i++) {
                Locked pkg = pkgs.get(i);
                for (String vulnId : results.get(i).vulnIds()) {
                    OsvClient.Vulnerability v =
                            Objects.requireNonNull(futures.get(vulnId), vulnId).get();
                    findings.add(new AuditReport.Finding(
                            pkg.ga(),
                            pkg.version(),
                            vulnId,
                            v.summary(),
                            AuditReport.Severity.parse(v.severity()),
                            fixedIn(v.fixedVersions(pkg.ga()), pkg.version())));
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

    /** One locked package as OSV keys it: {@code group:artifact} and version. */
    private record Locked(String ga, String version) {}

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
