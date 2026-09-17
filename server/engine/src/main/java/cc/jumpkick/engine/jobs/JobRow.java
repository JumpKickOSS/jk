// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.wire.protocol.JobQueuedFrame;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One job as {@code jk engine status}, {@code GET /api/status} and a queued client's wire line see
 * it: live (admitted, holding its memory share) or queued (waiting for memory behind {@code ahead}
 * earlier jobs). {@code sinceMillis} is when it was admitted or when it arrived in the queue;
 * {@code workers} counts its forked processes still alive and {@code lastEventAt} its last task
 * event, both {@code -1} for a queued job; {@code ahead} is {@code -1} for a live one.
 */
public record JobRow(
        long jid, String kind, String dir, boolean live, long sinceMillis, int workers, long lastEventAt, int ahead) {

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    public static JobRow live(long jid, String kind, String dir, long sinceMillis, int workers, long lastEventAt) {
        return new JobRow(jid, kind, dir, true, sinceMillis, workers, lastEventAt, -1);
    }

    public static JobRow queued(long jid, String kind, String dir, long sinceMillis, int ahead) {
        return new JobRow(jid, kind, dir, false, sinceMillis, -1, -1, ahead);
    }

    /** The row as the status vitals carry it; {@code state} is {@code live} or {@code queued}. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jid", jid);
        m.put("kind", kind);
        m.put("dir", dir);
        m.put("state", live ? "live" : "queued");
        m.put("since", sinceMillis);
        m.put("workers", workers);
        m.put("lastEventAt", lastEventAt);
        m.put("ahead", ahead);
        return m;
    }

    public static List<Map<String, Object>> toJson(List<JobRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (JobRow r : rows) out.add(r.toJson());
        return out;
    }

    /** The live rows as a queued job's wire line names them. */
    public static List<JobQueuedFrame.Live> toWire(List<JobRow> rows) {
        List<JobQueuedFrame.Live> out = new ArrayList<>();
        for (JobRow r : rows)
            if (r.live()) out.add(new JobQueuedFrame.Live(r.jid(), r.kind(), r.dir(), r.sinceMillis()));
        return out;
    }

    /** {@code test /home/me/app (jid 739) since 22:36} — how a log line or an error names this job. */
    public String describe() {
        return kind + " " + dir + " (jid " + jid + ") since " + wallClock(sinceMillis);
    }

    /** {@code since} as a local wall-clock time, {@code HH:mm}. */
    public static String wallClock(long epochMillis) {
        return WALL_CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** {@code 2h 05m} / {@code 12m} / {@code 40s}: a duration for a status row or a log line. */
    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }
}
