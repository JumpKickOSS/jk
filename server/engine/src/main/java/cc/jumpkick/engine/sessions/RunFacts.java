// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import cc.jumpkick.engine.journal.BuildRecord;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A finished run record as episode inference reads it, and as the session's timeline shows it. */
final class RunFacts {

    /** Characters of a headline the timeline keeps. */
    static final int HEADLINE_CHARS = 120;

    private RunFacts() {}

    static Fact fact(BuildRecord r) {
        return Fact.run(r.startedAt(), r.millis(), r.dir(), module(r), r.success(), failingTests(r));
    }

    static ActionRecord action(BuildRecord r, String episode) {
        String outcome = r.cancelled() ? "cancelled" : r.success() ? Episode.GREEN : Episode.RED;
        String summary = r.kind() + (r.buildNumber() > 0 ? " #" + r.buildNumber() : "");
        return new ActionRecord(
                0,
                r.startedAt(),
                ActionRecord.RUN,
                summary,
                headline(r),
                outcome,
                r.millis(),
                episode,
                r.dir() == null || r.dir().isBlank() ? null : r.dir(),
                r.buildNumber());
    }

    /** The failing tests of a run, {@code SimpleClass#method}, each once, in the order the record lists them. */
    static List<String> failingTests(BuildRecord r) {
        List<String> out = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!"error".equals(d.severity()) || blank(d.test())) continue;
            String id = testId(d);
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** The module a run's {@code Working in} names: its one failed module, else the project it ran in. */
    static String module(BuildRecord r) {
        BuildRecord.Module failed = null;
        int failures = 0;
        for (BuildRecord.Module m : r.modules()) {
            if (!m.success()) {
                failed = m;
                failures++;
            }
        }
        if (failures == 1 && failed != null) {
            String name = nameOf(failed.coord());
            if (name != null) return name;
            if (!failed.dir().isBlank()) return EpisodeInference.baseName(failed.dir());
        }
        String name = nameOf(r.coord());
        return name != null ? name : EpisodeInference.baseName(r.dir());
    }

    private static @Nullable String headline(BuildRecord r) {
        BuildRecord.Tests t = r.tests();
        if (t != null && t.failed() > 0) return t.failed() + " of " + t.total() + " tests failed";
        for (BuildRecord.Diag d : r.diagnostics()) {
            if ("error".equals(d.severity()) && !blank(d.message())) return firstLine(d.message());
        }
        if (t != null && t.total() > 0) return t.total() + " tests passed";
        return null;
    }

    static String firstLine(String text) {
        String line = text.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl).strip();
        return line.length() > HEADLINE_CHARS ? line.substring(0, HEADLINE_CHARS - 1) + "…" : line;
    }

    private static String testId(BuildRecord.Diag d) {
        String cls = d.className() == null ? "" : d.className().strip();
        int dot = cls.lastIndexOf('.');
        if (dot >= 0) cls = cls.substring(dot + 1);
        String method = d.method();
        String test = d.test();
        String m = method != null && !method.isBlank() ? method.strip() : test == null ? "" : test.strip();
        if (m.endsWith("()")) m = m.substring(0, m.length() - 2);
        if (cls.isEmpty()) return m;
        if (m.isEmpty()) return cls;
        return cls + "#" + m;
    }

    private static @Nullable String nameOf(@Nullable String coord) {
        if (coord == null || coord.isBlank()) return null;
        int colon = coord.lastIndexOf(':');
        String name = colon >= 0 ? coord.substring(colon + 1) : coord;
        return name.isBlank() || "unknown".equals(name) ? null : name;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
