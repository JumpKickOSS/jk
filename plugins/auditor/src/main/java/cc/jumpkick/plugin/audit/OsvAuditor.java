// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk audit} orchestration: lockfile → OSV batch query → concurrent detail fetch for hits,
 * each answered from the client's cache while OSV reports the advisory unmodified. Findings emit in
 * deterministic package × result order.
 */
public final class OsvAuditor {

    /**
     * Advisory detail fetches in flight at once: enough to hide OSV's latency, few enough that a
     * lockfile with hundreds of advisories does not open hundreds of connections.
     */
    static final int MAX_FETCHES = 8;

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
        Map<String, String> modified = new HashMap<>();
        for (OsvClient.Result r : results) {
            uniqueIds.addAll(r.vulnIds());
            modified.putAll(r.modified());
        }
        if (uniqueIds.isEmpty()) return new AuditReport(List.of());

        // The detail fetches, at most MAX_FETCHES of them in flight. Each takes its slot here, in
        // order, before it is started, and gives it up when it ends: the first failure is recorded
        // before its slot comes free, so no fetch after it is started.
        Semaphore slots = new Semaphore(MAX_FETCHES);
        AtomicReference<@Nullable Exception> failure = new AtomicReference<>();
        Map<String, CompletableFuture<OsvClient.Vulnerability>> futures = new LinkedHashMap<>();

        // Findings in package × vulnId order; the supply-chain report sidecar depends on it.
        List<AuditReport.Finding> findings = new ArrayList<>();
        boolean complete = false;
        try {
            for (String id : uniqueIds) {
                slots.acquire();
                if (failure.get() != null) {
                    slots.release();
                    break;
                }
                String stamp = modified.get(id);
                try {
                    futures.put(
                            id, CompletableFuture.supplyAsync(() -> fetch(id, stamp, slots, failure), JkThreads.io()));
                } catch (RuntimeException notStarted) {
                    slots.release();
                    throw notStarted;
                }
            }
            for (int i = 0; i < pkgs.size(); i++) {
                Locked pkg = pkgs.get(i);
                for (String vulnId : results.get(i).vulnIds()) {
                    CompletableFuture<OsvClient.Vulnerability> fetched = futures.get(vulnId);
                    if (fetched == null) throw new CancellationException("another advisory fetch failed");
                    OsvClient.Vulnerability v = fetched.get();
                    findings.add(new AuditReport.Finding(
                            pkg.ga(),
                            pkg.version(),
                            vulnId,
                            v.summary(),
                            AuditReport.Severity.parse(v.severity()),
                            fixedIn(v.fixedVersions(pkg.ga()), pkg.version()),
                            v.aliases()));
                }
            }
            complete = true;
        } catch (ExecutionException | CancellationException e) {
            // The fetch that failed first, not whichever one this loop reached first: a fetch that
            // was stopped because of it says only that.
            Throwable cause = failure.get();
            if (cause == null) cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ie) throw ie;
            throw new IOException("audit vuln-detail fetch failed: " + cause.getMessage(), cause);
        } finally {
            if (!complete) {
                failure.compareAndSet(null, new CancellationException("the audit stopped"));
                for (CompletableFuture<OsvClient.Vulnerability> f : futures.values()) f.cancel(false);
            }
        }
        return new AuditReport(findings);
    }

    /**
     * One advisory's details, in the slot the audit took for it, which it gives up when it ends. A
     * failure is recorded in {@code failure} before the slot comes free, so the audit starts no
     * fetch after it.
     */
    private OsvClient.Vulnerability fetch(
            String id, @Nullable String modified, Semaphore slots, AtomicReference<@Nullable Exception> failure) {
        try {
            if (failure.get() != null) throw new CancellationException("another advisory fetch failed");
            return client.fetchVuln(id, modified);
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (IOException | InterruptedException | RuntimeException e) {
            failure.compareAndSet(null, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } finally {
            slots.release();
        }
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
