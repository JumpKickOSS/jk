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

    static String write(BuildRecord r) {
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
        o.put("modules", modules);

        o.put("tasks", stepList(r.steps()));

        List<Object> diagnostics = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("severity", d.severity());
            dm.put("dir", d.dir());
            dm.put("task", d.step());
            dm.put("code", d.code());
            dm.put("message", d.message());
            if (d.test() != null && !d.test().isEmpty()) dm.put("test", d.test());
            if (d.module() != null && !d.module().isEmpty()) dm.put("module", d.module());
            if (d.engine() != null && !d.engine().isEmpty()) dm.put("engine", d.engine());
            if (d.className() != null && !d.className().isEmpty())
                dm.put(EngineProtocol.TEST_CLASS_FIELD, d.className());
            if (d.method() != null && !d.method().isEmpty()) dm.put("method", d.method());
            if (d.exceptionClass() != null && !d.exceptionClass().isEmpty())
                dm.put("exceptionClass", d.exceptionClass());
            if (d.file() != null && !d.file().isEmpty()) dm.put("file", d.file());
            if (d.line() > 0) dm.put("line", d.line());
            if (d.col() > 0) dm.put("col", d.col());
            if (d.snippetStart() > 0) dm.put("snippetStart", d.snippetStart());
            if (d.snippet() != null && !d.snippet().isEmpty()) dm.put("snippet", d.snippet());
            if (d.worker() > 0) dm.put("worker", d.worker());
            if (d.stack() != null && !d.stack().isEmpty()) dm.put("stack", d.stack());
            diagnostics.add(dm);
        }
        o.put("diagnostics", diagnostics);

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

        return MiniJson.writePretty(o);
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
        if (!(o.get("delta") instanceof Map<?, ?> dm)) return null;
        Map<String, Object> d = (Map<String, Object>) dm;
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

    private static JobDelta.@Nullable Rows rows(Map<String, Object> d, String key) {
        if (!(d.get(key) instanceof Map<?, ?> rm)) return null;
        Map<String, Object> r = (Map<String, Object>) rm;
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
            out.add(pm);
        }
        return out;
    }

    // ---------------------------------------------------------------- read

    @SuppressWarnings("unchecked")
    static BuildRecord read(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("record.json is not a JSON object");
        }
        Map<String, Object> o = (Map<String, Object>) m;

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
                    (int) lng(dm, "worker")));
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

    private static @Nullable String str(Map<String, Object> o, String key) {
        return o.get(key) instanceof String s ? s : null;
    }

    private static long lng(Map<String, Object> o, String key) {
        return o.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<String, Object> o, String key) {
        return Boolean.TRUE.equals(o.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> o, String key) {
        return o.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static List<String> strList(Map<String, Object> o, String key) {
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
                    pm.get("waitMillis") instanceof Number w ? w.longValue() : 0L));
        }
        return steps;
    }
}
