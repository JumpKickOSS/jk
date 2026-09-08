// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import org.jspecify.annotations.Nullable;

/**
 * The small per-command event/result contracts a front-end consumes from hosted commands, extracted from
 * the engine's plan factories ({@code AuditPlans}/{@code FormatPlans}/{@code CompatPlans}) for the
 * slim client (Stage 5). The engine keeps its own equivalent nested types; the in-process seam
 * adapts between the two with method references, so neither side links the other.
 */
public final class HostedEvents {

    private HostedEvents() {}

    /** One {@code jk audit} finding, streamed as it is parsed from the auditor worker. */
    public interface FindingObserver {
        void onFinding(String module, String version, String vulnId, String severity, String summary);
    }

    /** One {@code jk format} per-file result, streamed as the formatter worker reports it. */
    public interface FileObserver {
        void onFile(String path, String status, @Nullable String message, int index, int total);
    }

    /** One {@code jk import} progress note (kind = {@code note}/{@code warning}/…). */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** A provisioned Maven/Gradle distribution ({@code jk mvn}/{@code jk gradle}). */
    public record Provision(
            @Nullable String bin,
            @Nullable String version,
            @Nullable String source,
            @Nullable String error,
            int exit) {}
}
