// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.HttpLive;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** In-flight run snapshots for dashboard SSE connect. */
@RequiredArgsConstructor
public final class LiveRuns {

    private final InFlightBuilds inFlight;
    private final JobSessions sessions;
    private final @Nullable HttpEvents events;
    private final ReentrantReadWriteLock sseConnect;
    private final LongSupplier clock;

    public List<HttpLive.Run> snapshot() {
        List<HttpLive.Run> out = new ArrayList<>();
        for (InFlightBuilds.Hold h : inFlight.list()) {
            Double p = sessions.lastProgress(h.requestId());
            long remainingMs = -1L;
            long r0Ms = -1L;
            long num = 0L;
            long den = 0L;
            var tracker = sessions.trackerOrNull(h.requestId());
            if (tracker != null) {
                var snap = tracker.snapshot();
                remainingMs = snap.remainingMs();
                r0Ms = snap.R0ms();
                num = snap.numerator();
                den = snap.denominator();
                if ((p == null || p.isNaN()) && snap.hasPercent()) p = snap.percent();
            }
            BuildAccumulator acc = sessions.accumulator(h.requestId());
            BuildAccumulator.MidFlight mid =
                    acc != null ? acc.midFlight() : new BuildAccumulator.MidFlight(List.of(), List.of());
            out.add(new HttpLive.Run(
                    h.requestId(),
                    h.buildNumber(),
                    h.kind(),
                    h.dir(),
                    h.coord(),
                    h.startedAt(),
                    sessions.lastEventAt(h.requestId()),
                    p != null && !p.isNaN() ? p : Double.NaN,
                    h.journalId(),
                    remainingMs,
                    r0Ms,
                    num,
                    den,
                    mid.modules(),
                    mid.tasks()));
        }
        return out;
    }

    public void rehydrate(HttpEvents.Subscription sub) {
        if (sub == null || events == null) return;
        sseConnect.writeLock().lock();
        try {
            for (HttpLive.Run run : snapshot()) {
                events.deliverTo(sub, "run-snapshot", snapshotJson(run, clock.getAsLong()));
            }
            events.attach(sub);
        } finally {
            sseConnect.writeLock().unlock();
        }
    }

    static JsonOut snapshotJson(HttpLive.Run run, long serverNow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("type", "run-snapshot");
        m.put("jid", run.requestId());
        m.put("kind", run.kind() == null ? "build" : run.kind());
        m.put("dir", run.dir() == null ? "" : run.dir());
        if (run.coord() != null) m.put("coord", run.coord());
        if (run.startedAt() > 0) {
            m.put("startedAt", run.startedAt());
            m.put("serverNow", serverNow);
        }
        if (run.dir() != null && !run.dir().isBlank()) {
            m.put("projectId", cc.jumpkick.runtime.ProjectIds.idOf(run.dir()));
        }
        m.put("running", true);
        if (run.buildNumber() > 0) m.put("buildNumber", run.buildNumber());
        if (run.journalId() != null && !run.journalId().isBlank()) m.put("historyId", run.journalId());
        if (!Double.isNaN(run.progress())) m.put("progress", run.progress());
        if (run.remainingMs() >= 0) m.put("remainingMs", run.remainingMs());
        if (run.r0Ms() > 0) m.put("R0", run.r0Ms());
        if (run.denominator() > 0) {
            m.put("numerator", run.numerator());
            m.put("denominator", run.denominator());
        }
        if (!run.modules().isEmpty()) {
            List<Object> mods = new ArrayList<>(run.modules().size());
            for (var mod : run.modules()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("dir", mod.dir() == null ? "" : mod.dir());
                if (mod.coord() != null) mm.put("coord", mod.coord());
                mm.put("finished", mod.finished());
                mm.put("success", mod.finished() && mod.success());
                mm.put("millis", mod.millis());
                if (mod.finished()) mm.put("didWork", mod.didWork());
                List<Object> tasks = new ArrayList<>(mod.tasks().size());
                for (var t : mod.tasks()) {
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("name", t.name());
                    tm.put("stage", t.stage() == null ? "" : t.stage());
                    tm.put("status", t.status() == null ? "RUN" : t.status());
                    tm.put("millis", t.millis());
                    tasks.add(tm);
                }
                mm.put("tasks", tasks);
                mods.add(mm);
            }
            m.put("modules", mods);
        } else if (!run.tasks().isEmpty()) {
            List<Object> tasks = new ArrayList<>(run.tasks().size());
            for (var t : run.tasks()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.name());
                tm.put("stage", t.stage() == null ? "" : t.stage());
                tm.put("status", t.status() == null ? "RUN" : t.status());
                tm.put("millis", t.millis());
                tasks.add(tm);
            }
            m.put("tasks", tasks);
        }
        return JsonOut.rawObject(m);
    }
}
