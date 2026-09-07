// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.runtime.BuildNumberAllocator;
import cc.jumpkick.runtime.ProjectIds;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.nio.file.Path;

/** Exclusive fingerprint + start-time journal stub for one job. */
public final class JobAdmit {

    private JobAdmit() {}

    /**
     * Allocate a build number (journaled kinds), take an exclusive fingerprint slot when required,
     * and persist an in-flight journal stub.
     */
    public static AdmitResult admit(
            JobEnvelope.Host host, long requestId, String kind, String dir, String fingerprint, String trigger) {
        boolean exclusive = BuildJobFingerprint.isExclusiveKind(kind);
        String fp = exclusive && fingerprint != null ? fingerprint : "";
        // Reject before allocating a build number so collisions do not burn sequence values.
        if (exclusive && !fp.isEmpty()) {
            var existing = host.inFlight().peek(fp);
            if (existing.isPresent()) return AdmitResult.reject(existing.get());
        }
        String canonDir = BuildJobFingerprint.canonicalDir(dir);
        String coord = host.coordOf(dir);
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
                            buildNumber, kind, dir, coord, projectId, startedAt, host.version(), trigger, requestId));
        }
        InFlightBuilds.Hold candidate =
                new InFlightBuilds.Hold(requestId, buildNumber, fp, kind, dir, coord, startedAt, journalId, trigger);
        if (exclusive && !fp.isEmpty()) {
            var raced = host.inFlight().tryAcquire(candidate);
            if (raced.isPresent()) {
                // Scoped: journalId is this project's build number, which another project may
                // also use.
                if (journalId != null) host.journal().delete(journalId, coord, dir);
                return AdmitResult.reject(raced.get());
            }
        } else {
            host.inFlight().tryAcquire(candidate);
        }
        return AdmitResult.ok(buildNumber, journalId);
    }

    /** Job-start wire line with buildNumber + details path for the CLI transcript. */
    public static String jobStartLine(JobEnvelope.Host host, long jid, String kind, String dir, AdmitResult admit) {
        String detailsPath = null;
        if (admit.buildNumber() > 0) {
            detailsPath = host.journal()
                    .detailsFile(host.coordOf(dir), dir, admit.buildNumber())
                    .map(Path::toString)
                    .orElseGet(() -> host.journal()
                            .detailsFile(Long.toString(admit.buildNumber()))
                            .map(Path::toString)
                            .orElse(null));
        }
        return ProtoLifecycle.jobStart(jid, kind, dir, admit.buildNumber(), detailsPath, -1);
    }
}
