// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.runtime.base.BuildNumberAllocator;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.transcript.SessionStartLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** Exclusive fingerprint + start-time journal stub for one job. */
public final class JobAdmit {

    private JobAdmit() {}

    /**
     * Allocate a build number (journaled kinds), take an exclusive fingerprint slot when required
     * — in this engine's table and, through {@link BuildSlot}, against every other engine on the
     * machine — and persist an in-flight journal stub.
     */
    public static AdmitResult admit(
            JobEnvelope.Host host,
            long requestId,
            String kind,
            String dir,
            String fingerprint,
            String trigger,
            @Nullable String session) {
        boolean exclusive = BuildJobFingerprint.isExclusiveKind(kind);
        String fp = exclusive && fingerprint != null ? fingerprint : "";
        // Reject before allocating a build number so collisions do not burn sequence values.
        if (exclusive && !fp.isEmpty()) {
            var existing = host.inFlight().peek(fp);
            if (existing.isPresent()) return AdmitResult.reject(existing.get());
        }
        String canonDir = BuildJobFingerprint.canonicalDir(dir);
        String coord = host.coordOf(dir);
        // Another engine on this machine may hold the checkout: its slot lock says so, and names
        // the build it is running.
        BuildSlot slot = null;
        if (exclusive && !fp.isEmpty() && canonDir != null && !canonDir.isBlank()) {
            Path checkout = Path.of(canonDir);
            switch (BuildSlot.take(checkout)) {
                case BuildSlot.Taken taken -> slot = taken.slot();
                case BuildSlot.Held held -> {
                    InFlightBuilds.Hold holder = BuildSlot.holderOf(checkout, fp, dir, coord);
                    if (holder.buildNumber() == 0) {
                        // Taken by this engine moments ago, before its number was written.
                        holder = host.inFlight().peek(fp).orElse(holder);
                    }
                    return AdmitResult.reject(holder);
                }
                case BuildSlot.Unguarded unguarded -> {
                    if (UNGUARDED_NOTED.add(canonDir)) {
                        host.log("build slot: " + canonDir + " builds without the cross-process lock ("
                                + unguarded.reason() + ")");
                    }
                }
            }
        }
        try {
            return admitHolding(host, requestId, kind, dir, fp, trigger, session, exclusive, canonDir, coord, slot);
        } catch (RuntimeException failed) {
            if (slot != null) slot.close();
            throw failed;
        }
    }

    /** Checkouts already noted as building unguarded, so the log says it once per engine. */
    private static final Set<String> UNGUARDED_NOTED = ConcurrentHashMap.newKeySet();

    private static AdmitResult admitHolding(
            JobEnvelope.Host host,
            long requestId,
            String kind,
            String dir,
            String fp,
            String trigger,
            @Nullable String session,
            boolean exclusive,
            @Nullable String canonDir,
            @Nullable String coord,
            @Nullable BuildSlot slot) {
        long buildNumber = 0L;
        if (BuildHistoryKinds.isBuildLike(kind) && canonDir != null && !canonDir.isBlank()) {
            buildNumber = BuildNumberAllocator.allocate(canonDir, coord);
        }
        long startedAt = host.nowMillis();
        String projectId = ProjectIds.refresh(canonDir != null ? canonDir : dir);
        String journalId = null;
        if (host.historyConfig().enabled() && canonDir != null && !canonDir.isBlank()) {
            journalId = host.journal()
                    .begin(BuildRecord.running(
                            buildNumber,
                            kind,
                            canonDir,
                            coord,
                            projectId,
                            startedAt,
                            host.version(),
                            trigger,
                            session,
                            requestId));
        }
        InFlightBuilds.Hold candidate = new InFlightBuilds.Hold(
                requestId, buildNumber, fp, kind, dir, coord, startedAt, journalId, trigger, session);
        if (exclusive && !fp.isEmpty()) {
            var raced = host.inFlight().tryAcquire(candidate);
            if (raced.isPresent()) {
                // Scoped: journalId is this project's build number, which another project may
                // also use.
                if (journalId != null) host.journal().delete(journalId, coord, dir);
                if (slot != null) slot.close();
                return AdmitResult.reject(raced.get());
            }
            if (slot != null) {
                slot.describe(candidate);
                host.inFlight().attachSlot(requestId, slot);
            }
        } else {
            host.inFlight().tryAcquire(candidate);
        }
        return AdmitResult.ok(buildNumber, journalId);
    }

    /**
     * Job-start wire line with buildNumber + details path for the CLI transcript. The path is the
     * run's own: build numbers are per project, so it is resolved under this project's home and
     * never by number across homes, where another project's run of the same number would answer. A
     * journaled job without a build number — a lock — names its run directory's file by the
     * journal id instead.
     */
    public static String jobStartLine(JobEnvelope.Host host, long jid, String kind, String dir, AdmitResult admit) {
        Path details = detailsFile(host, dir, admit);
        return ProtoLifecycle.jobStart(
                jid, kind, dir, admit.buildNumber(), details == null ? null : details.toString(), -1);
    }

    /** The admitted run's {@code details.jsonl}, or {@code null} when the run has no journal dir. */
    static @Nullable Path detailsFile(JobEnvelope.Host host, String dir, AdmitResult admit) {
        if (admit.buildNumber() > 0) {
            return host.journal()
                    .detailsFile(host.coordOf(dir), dir, admit.buildNumber())
                    .orElse(null);
        }
        if (admit.journalId() != null) {
            return host.journal().detailsFile(admit.journalId()).orElse(null);
        }
        return null;
    }

    /**
     * Open a detached run's transcript with the {@code session-start} header the CLI writes for its
     * own runs — the command, and the {@code trigger} and {@code session} that asked — so an MCP or
     * web run's {@code details.jsonl} says who asked the way a CLI run's does. Best-effort: a run
     * with no journal dir, or a disk that refuses, leaves no transcript and fails nothing.
     */
    public static void openDetachedTranscript(
            JobEnvelope.Host host,
            String kind,
            String dir,
            String trigger,
            @Nullable String session,
            AdmitResult admit) {
        Path details = detailsFile(host, dir, admit);
        if (details == null) return;
        String header = new SessionStartLine(host.nowMillis(), kind, List.of(kind), trigger, session).encode();
        try {
            Files.createDirectories(details.getParent());
            Files.writeString(
                    details,
                    header + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            host.log("jk engine: detached transcript header skipped: " + e);
        }
    }
}
