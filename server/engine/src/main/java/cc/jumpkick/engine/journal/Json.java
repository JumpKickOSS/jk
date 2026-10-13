// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes {@link BuildRecord} as JSON for the journal's {@code record.json}. Pure
 * shape-mapping: this class converts {@code BuildRecord} to/from the {@link MiniJson} object
 * model (Map/List/scalars) — serialization, parsing, and escaping all live in {@link MiniJson},
 * the engine's single JSON home.
 */
final class Json {

    private Json() {}

    // ---------------------------------------------------------------- write

    /**
     * First top-level key of a record's tail. Every field written before it is a scalar or a bounded
     * object, so a reader of the head ({@link #readHead}) never parses module rows or diagnostics.
     */
    static final String TAIL_KEY = "modules";

    /** Where the tail starts in pretty-printed record text: a top-level key sits at depth one. */
    private static final String TAIL_MARK = "\n  \"" + TAIL_KEY + "\":";

    /** A record's text, and the diagnostics past its budget as a JSON array ({@code null} when none spilled). */
    record Written(String record, @Nullable String spill) {}

    /** The whole record, every diagnostic inline. */
    static String write(BuildRecord r) {
        return write(r, Long.MAX_VALUE, "").record();
    }

    /**
     * The record with its diagnostics cut at {@code diagnosticsBudget} characters of JSON; the rest
     * go to {@link Written#spill}, which the record names as {@code diagnosticsSpill = spillName}.
     */
    static Written write(BuildRecord r, long diagnosticsBudget, String spillName) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", r.id());
        o.put("buildNumber", r.buildNumber());
        o.put("schema", r.schema());
        o.put("kind", r.kind());
        o.put("dir", r.dir());
        o.put("coord", r.coord());
        o.put("projectId", r.projectId());
        o.put("startedAt", r.startedAt());
        o.put("finishedAt", r.finishedAt());
        o.put("millis", r.millis());
        o.put("success", r.success());
        o.put("cancelled", r.cancelled());
        o.put("exitCode", r.exitCode());
        o.put("jkVersion", r.jkVersion());

        if (r.tests() == null) {
            o.put(TestSummary.WIRE_KEY, null);
        } else {
            o.put(
                    TestSummary.WIRE_KEY,
                    TestSummary.countsMap(
                            r.tests().total(),
                            r.tests().succeeded(),
                            r.tests().failed(),
                            r.tests().skipped()));
        }

        o.put("trigger", r.trigger());
        if (r.session() != null) o.put("session", r.session());
        o.put("commit", r.commit());
        o.put("running", r.running());
        if (r.requestId() > 0) o.put("requestId", r.requestId());

        if (r.benefit() == null) {
            o.put("benefit", null);
        } else {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("estimatedUncachedMillis", r.benefit().estimatedUncachedMillis());
            b.put("savedMillis", r.benefit().savedMillis());
            b.put("coveredSkips", r.benefit().coveredSkips());
            b.put("totalSkips", r.benefit().totalSkips());
            o.put("benefit", b);
        }

        if (r.io() == null) {
            o.put("io", null);
        } else {
            Map<String, Object> io = new LinkedHashMap<>();
            io.put("remoteUp", r.io().remoteUp());
            io.put("remoteDown", r.io().remoteDown());
            io.put("localUp", r.io().localUp());
            io.put("localDown", r.io().localDown());
            o.put("io", io);
        }

        if (r.publish() != null) o.put("publish", publishMap(r.publish()));
        if (r.delta() != null) o.put("delta", deltaMap(r.delta()));

        o.put("moduleCount", r.modules().size());
        o.put("failedModules", r.modules().stream().filter(m -> !m.success()).count());

        List<Object> diagnostics = new ArrayList<>();
        List<Object> spilled = new ArrayList<>();
        long used = 0;
        for (BuildRecord.Diag d : r.diagnostics()) {
            Map<String, Object> dm = diagMap(d);
            if (spilled.isEmpty()) {
                if (diagnosticsBudget != Long.MAX_VALUE)
                    used += MiniJson.write(dm).length();
                if (used <= diagnosticsBudget) {
                    diagnostics.add(dm);
                    continue;
                }
            }
            spilled.add(dm);
        }
        if (!spilled.isEmpty()) o.put("diagnosticsSpill", spillName);

        // The tail: everything below grows with the build and is never part of a head read.
        List<Object> modules = new ArrayList<>();
        for (BuildRecord.Module m : r.modules()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("coord", m.coord());
            mm.put("dir", m.dir());
            mm.put("success", m.success());
            mm.put("exitCode", m.exitCode());
            mm.put("millis", m.millis());
            mm.put("tasks", stepList(m.steps()));
            modules.add(mm);
        }
        o.put(TAIL_KEY, modules);

        o.put("tasks", stepList(r.steps()));

        if (!r.coverage().isEmpty()) {
            List<Object> coverage = new ArrayList<>();
            for (BuildRecord.Coverage c : r.coverage()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("dir", c.dir());
                cm.put("label", c.label());
                cm.put("linesCovered", c.linesCovered());
                cm.put("linesMissed", c.linesMissed());
                cm.put("branchesCovered", c.branchesCovered());
                cm.put("branchesMissed", c.branchesMissed());
                cm.put("html", c.html());
                coverage.add(cm);
            }
            o.put("coverage", coverage);
        }

        o.put("diagnostics", diagnostics);

        return new Written(MiniJson.writePretty(o), spilled.isEmpty() ? null : MiniJson.write(spilled));
    }

    private static Map<String, Object> diagMap(BuildRecord.Diag d) {
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("severity", d.severity());
        dm.put("dir", d.dir());
        dm.put("task", d.step());
        dm.put("code", d.code());
        if (!d.key().isEmpty()) dm.put("key", d.key());
        dm.put("message", d.message());
        if (d.test() != null && !d.test().isEmpty()) dm.put("test", d.test());
        if (d.module() != null && !d.module().isEmpty()) dm.put("module", d.module());
        if (!d.suite().isEmpty()) dm.put("suite", d.suite());
        if (d.engine() != null && !d.engine().isEmpty()) dm.put("engine", d.engine());
        if (d.className() != null && !d.className().isEmpty()) dm.put(EngineProtocol.TEST_CLASS_FIELD, d.className());
        if (d.method() != null && !d.method().isEmpty()) dm.put("method", d.method());
        if (d.exceptionClass() != null && !d.exceptionClass().isEmpty()) dm.put("exceptionClass", d.exceptionClass());
        if (d.file() != null && !d.file().isEmpty()) dm.put("file", d.file());
        if (d.line() > 0) dm.put("line", d.line());
        if (d.col() > 0) dm.put("col", d.col());
        if (d.snippetStart() > 0) dm.put("snippetStart", d.snippetStart());
        if (d.snippet() != null && !d.snippet().isEmpty()) dm.put("snippet", d.snippet());
        if (d.worker() > 0) dm.put("worker", d.worker());
        if (d.stack() != null && !d.stack().isEmpty()) dm.put("stack", d.stack());
        return dm;
    }

    /**
     * The {@code delta} object: the run before and, per list, {@code count} with the {@code shown}
     * head. An absent comparison ({@code files}, the test rows) is an absent key.
     */
    static Map<String, Object> deltaMap(JobDelta d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previousBuildNumber", d.previousBuildNumber());
        m.put("previousSuccess", d.previousSuccess());
        m.put("previousMillis", d.previousMillis());
        putRows(m, "files", d.files());
        putRows(m, "appeared", d.appeared());
        putRows(m, "gone", d.gone());
        putRows(m, "broke", d.broke());
        putRows(m, "fixed", d.fixed());
        putRows(m, "added", d.added());
        putRows(m, "dropped", d.dropped());
        return m;
    }

    private static void putRows(Map<String, Object> m, String key, JobDelta.@Nullable Rows rows) {
        if (rows == null) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", rows.count());
        r.put("shown", new ArrayList<Object>(rows.shown()));
        m.put(key, r);
    }

    static @Nullable JobDelta readDelta(Map<String, Object> o) {
        if (!(o.get("delta") instanceof Map<?, ?> d)) return null;
        JobDelta.Rows appeared = rows(d, "appeared");
        JobDelta.Rows gone = rows(d, "gone");
        return new JobDelta(
                lng(d, "previousBuildNumber"),
                bool(d, "previousSuccess"),
                lng(d, "previousMillis"),
                rows(d, "files"),
                appeared == null ? new JobDelta.Rows(0, List.of()) : appeared,
                gone == null ? new JobDelta.Rows(0, List.of()) : gone,
                rows(d, "broke"),
                rows(d, "fixed"),
                rows(d, "added"),
                rows(d, "dropped"));
    }

    private static JobDelta.@Nullable Rows rows(Map<?, ?> d, String key) {
        if (!(d.get(key) instanceof Map<?, ?> r)) return null;
        return new JobDelta.Rows((int) lng(r, "count"), strList(r, "shown"));
    }

    /** The {@code publish} object: what a publish run sent where, absent keys for absent facts. */
    private static Map<String, Object> publishMap(BuildRecord.Publish p) {
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("destination", p.destination());
        pub.put("files", p.files());
        pub.put("dryRun", p.dryRun());
        if (p.deploymentId() != null) pub.put("deploymentId", p.deploymentId());
        if (p.deploymentState() != null) pub.put("deploymentState", p.deploymentState());
        if (!p.deploymentErrors().isEmpty()) pub.put("deploymentErrors", p.deploymentErrors());
        if (!p.bundle().isEmpty()) pub.put("bundle", p.bundle());
        return pub;
    }

    private static List<Object> stepList(List<BuildRecord.Task> steps) {
        List<Object> out = new ArrayList<>(steps.size());
        for (BuildRecord.Task p : steps) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("stage", p.stage());
            pm.put("status", p.status());
            // Unknown duration (< 0) stays ABSENT on the wire: stamping it 0 made the SPA paint
            // genuinely-worked steps as dashed cache-skips — 0 means a true no-op.
            if (p.millis() >= 0) pm.put("millis", p.millis());
            if (p.waitMillis() > 0) pm.put("waitMillis", p.waitMillis());
            if (!p.detail().isEmpty()) pm.put("detail", p.detail());
            out.add(pm);
        }
        return out;
    }

    // ---------------------------------------------------------------- read

    static BuildRecord read(String json) {
        return read(json, null);
    }

    /** A whole record, the diagnostics its writer spilled ({@code spill}, a JSON array) appended. */
    static BuildRecord read(String json, @Nullable String spill) {
        Map<?, ?> m = object(json);
        return readMap(spill == null ? m : withSpill(m, spill));
    }

    /** Record text with the diagnostics its writer spilled ({@code spill}, a JSON array) inline. */
    static String writeWithSpill(String json, String spill) {
        return MiniJson.writePretty(withSpill(object(json), spill));
    }

    /** {@code record} with {@code spill}'s diagnostics after its own, no longer naming a spill. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withSpill(Map<?, ?> record, String spill) {
        Map<String, Object> o = new LinkedHashMap<>((Map<String, Object>) record);
        o.remove("diagnosticsSpill");
        if (MiniJson.parse(spill) instanceof List<?> rest) {
            List<Object> all = new ArrayList<>(arr(o, "diagnostics"));
            all.addAll(rest);
            o.put("diagnostics", all);
        }
        return o;
    }

    private static Map<?, ?> object(String json) {
        if (!(MiniJson.parse(json) instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("record.json is not a JSON object");
        }
        return m;
    }

    /**
     * The head of record text that reaches its tail, parsed: module rows, tasks, coverage and
     * diagnostics read back empty. {@code null} when {@code text} stops before the tail.
     */
    static BuildJournal.@Nullable Head readHead(String text) {
        int cut = tailStart(text);
        if (cut < 0) return null;
        Object root = MiniJson.parse(text.substring(0, cut) + "\n}");
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("record.json is not a JSON object");
        }
        return new BuildJournal.Head(readMap(m), (int) lng(m, "moduleCount"), (int) lng(m, "failedModules"));
    }

    /** Index of the comma ending a record's head, or -1 when {@code text} stops before the tail. */
    static int tailStart(String text) {
        int at = text.indexOf(TAIL_MARK);
        return at > 0 && text.charAt(at - 1) == ',' ? at - 1 : -1;
    }

    /** A journal row already parsed into the JSON object model. */
    @SuppressWarnings("unchecked")
    static BuildRecord readMap(Map<?, ?> raw) {
        if (raw == null) throw new IllegalArgumentException("record.json is not a JSON object");
        Map<String, Object> o = (Map<String, Object>) raw;

        TestSummary counts = TestSummary.countsFromMap(o.get(TestSummary.WIRE_KEY));
        BuildRecord.Tests tests = counts == null
                ? null
                : new BuildRecord.Tests(counts.total(), counts.succeeded(), counts.failed(), counts.skipped());

        List<BuildRecord.Module> modules = new ArrayList<>();
        for (Object e : arr(o, "modules")) {
            Map<String, Object> mm = (Map<String, Object>) e;
            modules.add(new BuildRecord.Module(
                    str(mm, "coord"),
                    text(mm, "dir"),
                    bool(mm, "success"),
                    (int) lng(mm, "exitCode"),
                    lng(mm, "millis"),
                    readSteps(mm)));
        }

        List<BuildRecord.Task> steps = readSteps(o);

        BuildRecord.CacheBenefit benefit = null;
        if (o.get("benefit") instanceof Map<?, ?> bm) {
            Map<String, Object> b = (Map<String, Object>) bm;
            benefit = new BuildRecord.CacheBenefit(
                    lng(b, "estimatedUncachedMillis"), lng(b, "savedMillis"),
                    lng(b, "coveredSkips"), lng(b, "totalSkips"));
        }

        BuildRecord.Io io = null;
        if (o.get("io") instanceof Map<?, ?> im) {
            Map<String, Object> i = (Map<String, Object>) im;
            io = new BuildRecord.Io(lng(i, "remoteUp"), lng(i, "remoteDown"), lng(i, "localUp"), lng(i, "localDown"));
        }

        BuildRecord.Publish publish = null;
        if (o.get("publish") instanceof Map<?, ?> pm) {
            Map<String, Object> pub = (Map<String, Object>) pm;
            publish = new BuildRecord.Publish(
                    text(pub, "destination"),
                    (int) lng(pub, "files"),
                    bool(pub, "dryRun"),
                    str(pub, "deploymentId"),
                    str(pub, "deploymentState"),
                    strList(pub, "deploymentErrors"),
                    strList(pub, "bundle"));
        }
        List<BuildRecord.Coverage> coverage = new ArrayList<>();
        for (Object e : arr(o, "coverage")) {
            Map<String, Object> cm = (Map<String, Object>) e;
            coverage.add(new BuildRecord.Coverage(
                    text(cm, "dir"),
                    text(cm, "label"),
                    lng(cm, "linesCovered"),
                    lng(cm, "linesMissed"),
                    lng(cm, "branchesCovered"),
                    lng(cm, "branchesMissed"),
                    text(cm, "html")));
        }

        List<BuildRecord.Diag> diagnostics = new ArrayList<>();
        for (Object e : arr(o, "diagnostics")) {
            Map<String, Object> dm = (Map<String, Object>) e;
            diagnostics.add(new BuildRecord.Diag(
                    text(dm, "severity"),
                    text(dm, "dir"),
                    str(dm, "task"),
                    text(dm, "code"),
                    text(dm, "message"),
                    str(dm, "test"),
                    str(dm, "exceptionClass"),
                    str(dm, "module"),
                    str(dm, "engine"),
                    str(dm, EngineProtocol.TEST_CLASS_FIELD),
                    str(dm, "method"),
                    str(dm, "stack"),
                    text(dm, "file"),
                    (int) lng(dm, "line"),
                    (int) lng(dm, "col"),
                    (int) lng(dm, "snippetStart"),
                    strList(dm, "snippet"),
                    (int) lng(dm, "worker"),
                    text(dm, "key"),
                    text(dm, "suite")));
        }

        return new BuildRecord(
                str(o, "id"),
                lng(o, "buildNumber"),
                (int) lng(o, "schema"),
                text(o, "kind"),
                text(o, "dir"),
                str(o, "coord"),
                str(o, "projectId"),
                lng(o, "startedAt"),
                lng(o, "finishedAt"),
                lng(o, "millis"),
                bool(o, "success"),
                bool(o, "cancelled"),
                (int) lng(o, "exitCode"),
                text(o, "jkVersion"),
                tests,
                modules,
                steps,
                diagnostics,
                text(o, "trigger"),
                str(o, "session"),
                str(o, "commit"),
                benefit,
                bool(o, "running"),
                io,
                lng(o, "requestId"),
                publish,
                coverage,
                readDelta(o));
    }

    /**
     * {@code key}'s string value, or {@code ""} when the field is absent. A record component that
     * is never null reads a missing field as empty rather than carrying the absence forward — the
     * nullable {@link #str} stays for the components that really are optional.
     */
    private static String text(Map<String, Object> o, String key) {
        String s = str(o, key);
        return s == null ? "" : s;
    }

    private static @Nullable String str(Map<?, ?> o, String key) {
        return o.get(key) instanceof String s ? s : null;
    }

    private static long lng(Map<?, ?> o, String key) {
        return o.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<?, ?> o, String key) {
        return Boolean.TRUE.equals(o.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> o, String key) {
        return o.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static List<String> strList(Map<?, ?> o, String key) {
        if (!(o.get(key) instanceof List<?> l) || l.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object e : l) {
            if (e != null) out.add(String.valueOf(e));
        }
        return out;
    }

    /** Read the {@code "tasks"} array of a record or of a module object. */
    @SuppressWarnings("unchecked")
    private static List<BuildRecord.Task> readSteps(Map<String, Object> o) {
        List<BuildRecord.Task> steps = new ArrayList<>();
        for (Object e : arr(o, "tasks")) {
            Map<String, Object> pm = (Map<String, Object>) e;
            // Missing millis = unknown duration, kept as -1 — NOT 0, which is the true-no-op
            // signal the dashboard renders dashed.
            steps.add(new BuildRecord.Task(
                    text(pm, "name"),
                    text(pm, "stage"),
                    text(pm, "status"),
                    pm.get("millis") instanceof Number n ? n.longValue() : -1L,
                    pm.get("waitMillis") instanceof Number w ? w.longValue() : 0L,
                    text(pm, "detail")));
        }
        return steps;
    }
}
