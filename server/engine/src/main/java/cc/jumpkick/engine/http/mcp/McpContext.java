// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.http.AdmissionYield;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.ProgressTokenRegistry;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Everything an {@link McpTool} may reach: the engine collaborators the handler was built with,
 * plus the one process-wide {@link McpSession} bind. Constructor args are the DI framework, so a
 * tool holds no state of its own and {@link McpTools#standard()} can be a list of singletons.
 *
 * <p>The four {@code @Setter} fields are optional wiring the embedded server attaches after
 * construction ({@code cacheSnapshot}, {@code detailsFile}, {@code cacheGate}) or that only tests
 * shorten ({@code journalSettleMs}). Everything else is required and final.
 */
@Getter
public final class McpContext {

    /** Engine vitals, as {@code GET /api/status} sees them. */
    private final Supplier<StatusSnapshot> status;

    /** The one async-job admission point shared with the REST surface. */
    private final EngineHttpJobs jobs;

    /** Coord/description fallback, used only when a project card fails to parse. */
    private final Function<String, Map<String, Object>> projectLookup;

    /** Redacted raw journal rows, newest first. Read through {@link #history()}. */
    @Getter(AccessLevel.NONE)
    private final Supplier<List<String>> historyRaw;

    private final String version;

    private final ProgressTokenRegistry progressTokens;

    private final Supplier<List<HttpLive.Run>> liveRuns;

    private final AdmissionYield admissionYield;

    /**
     * Raw JSON of the finished journal record for a jid, or {@code null} while absent/running.
     * The journal-backed form memoizes the run dir so wait-loop polls are one file read, never a
     * full {@code historyRaw} re-scan.
     */
    private final LongFunction<String> finishedRecords;

    private final McpSession session = new McpSession();

    /**
     * Shared memoized cache/store walker (same supplier as {@code GET /api/cache}) — {@code
     * jk_disk}/{@code jk_doctor}/{@code jk://disk} must not re-walk multi-GiB stores per call.
     * Optional wiring; {@code null} falls back to a fresh exclusive capture.
     */
    @Setter
    private volatile @Nullable Supplier<CacheSnapshot> cacheSnapshot;

    /**
     * Journal locator to {@code details.jsonl} path for {@code jk_details}. Optional wiring;
     * unset resolves empty and the tool reports transcripts unavailable.
     */
    @Setter
    private volatile Function<String, Optional<Path>> detailsFile = locator -> Optional.empty();

    /**
     * The engine's plan-vs-maintenance lock ({@code cacheGate}); {@code jk_disk clean|nuke} must
     * hold its write side (plus {@code .prune.lock}) before deleting. {@code null} only in tests
     * with no engine — the file lock still applies there.
     */
    @Setter
    private volatile @Nullable ReentrantReadWriteLock cacheGate;

    /**
     * Journal-write settle budget for a {@code wait}. Tests shrink it — a wait whose jid
     * deliberately never lands a record otherwise sleeps out the full second per call.
     */
    @Setter
    private volatile long journalSettleMs = 1_000;

    public McpContext(
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Function<String, Map<String, Object>> projectLookup,
            Supplier<List<String>> historyRaw,
            @Nullable String version,
            @Nullable ProgressTokenRegistry progressTokens,
            @Nullable Supplier<List<HttpLive.Run>> liveRuns,
            @Nullable AdmissionYield admissionYield,
            @Nullable LongFunction<String> finishedRecords) {
        this.status = Objects.requireNonNull(status);
        this.jobs = Objects.requireNonNull(jobs);
        this.projectLookup = Objects.requireNonNull(projectLookup);
        this.historyRaw = Objects.requireNonNull(historyRaw);
        this.version = version == null ? "0" : version;
        this.progressTokens = progressTokens == null ? new ProgressTokenRegistry() : progressTokens;
        this.liveRuns = liveRuns == null ? List::of : liveRuns;
        this.admissionYield = admissionYield == null ? AdmissionYield.NONE : admissionYield;
        this.finishedRecords = finishedRecords != null ? finishedRecords : this::scanHistoryForJid;
    }

    /** Fallback {@link #finishedRecords}: re-scan the journal rows this context already reads. */
    private @Nullable String scanHistoryForJid(long jid) {
        Map<String, Object> rec = McpDiagnostics.findByRequestId(historyRaw.get(), jid);
        return rec == null ? null : MiniJson.write(rec);
    }

    /** The journal rows, read once per call site. */
    public List<String> history() {
        return historyRaw.get();
    }
}
