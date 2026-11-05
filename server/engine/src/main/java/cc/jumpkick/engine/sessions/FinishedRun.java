// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A finished run as the session log folds it: what episode inference and the timeline read from
 * its record, and nothing more. A displaced engine sends it to its successor in {@code
 * drain-status} as {@link #toMap()}.
 *
 * @param episode the episode its admission placed it in
 * @param module what {@code Working in <module>} names
 * @param failingTests its failing tests, {@code SimpleClass#method}, in report order
 * @param headline the first line of its answer, or null when it has none
 */
public record FinishedRun(
        String episode,
        String kind,
        @Nullable String dir,
        long buildNumber,
        long startedAt,
        long finishedAt,
        long millis,
        boolean success,
        boolean cancelled,
        String module,
        List<String> failingTests,
        @Nullable String headline) {

    public FinishedRun {
        failingTests = List.copyOf(failingTests);
    }

    /** The run {@code r} records, or null when it is still running or no episode holds it. */
    public static @Nullable FinishedRun of(BuildRecord r) {
        String episode = r.episode();
        if (episode == null || episode.isBlank() || r.running()) return null;
        return new FinishedRun(
                episode,
                r.kind(),
                r.dir(),
                r.buildNumber(),
                r.startedAt(),
                r.finishedAt(),
                r.millis(),
                r.success(),
                r.cancelled(),
                RunFacts.module(r),
                RunFacts.failingTests(r),
                RunFacts.headline(r));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episode", episode);
        m.put("kind", kind);
        if (dir != null) m.put("dir", dir);
        m.put("buildNumber", buildNumber);
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        m.put("millis", millis);
        m.put("success", success);
        m.put("cancelled", cancelled);
        m.put("module", module);
        m.put("failingTests", failingTests);
        if (headline != null) m.put("headline", headline);
        return m;
    }

    /** A run read back from {@link #toMap}, or null when it names no episode. */
    public static @Nullable FinishedRun of(Map<?, ?> m) {
        String episode = MiniJson.str(m, "episode");
        if (episode == null || episode.isBlank()) return null;
        List<String> failing = new ArrayList<>();
        if (m.get("failingTests") instanceof List<?> l) for (Object o : l) if (o != null) failing.add(o.toString());
        return new FinishedRun(
                episode,
                Episode.text(m, "kind"),
                MiniJson.str(m, "dir"),
                Episode.num(m, "buildNumber"),
                Episode.num(m, "startedAt"),
                Episode.num(m, "finishedAt"),
                Episode.num(m, "millis"),
                Boolean.TRUE.equals(m.get("success")),
                Boolean.TRUE.equals(m.get("cancelled")),
                Episode.text(m, "module"),
                failing,
                MiniJson.str(m, "headline"));
    }
}
