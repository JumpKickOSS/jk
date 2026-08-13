// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.plugin.protocol.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            o.put("tests", null);
        } else {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("total", r.tests().total());
            t.put("succeeded", r.tests().succeeded());
            t.put("failed", r.tests().failed());
            t.put("skipped", r.tests().skipped());
            o.put("tests", t);
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
            if (d.className() != null && !d.className().isEmpty()) {
                dm.put("testClass", d.className());
                dm.put("class", d.className());
            }
            if (d.method() != null && !d.method().isEmpty()) dm.put("method", d.method());
            if (d.exceptionClass() != null && !d.exceptionClass().isEmpty())
                dm.put("exceptionClass", d.exceptionClass());
            if (d.file() != null && !d.file().isEmpty()) dm.put("file", d.file());
            if (d.line() > 0) dm.put("line", d.line());
            if (d.snippetStart() > 0) dm.put("snippetStart", d.snippetStart());
            if (d.snippet() != null && !d.snippet().isEmpty()) dm.put("snippet", d.snippet());
            if (d.worker() > 0) dm.put("worker", d.worker());
            if (d.stack() != null && !d.stack().isEmpty()) {
                dm.put("stack", d.stack());
                Map<String, Object> th = new LinkedHashMap<>();
                th.put("class", d.exceptionClass() == null ? "" : d.exceptionClass());
                th.put("message", d.message() == null ? "" : d.message());
                th.put("stack", d.stack());
                dm.put("throwable", th);
            }
            diagnostics.add(dm);
        }
        o.put("diagnostics", diagnostics);

        o.put("trigger", r.trigger());
        o.put("commit", r.commit());
        o.put("running", r.running());

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

        return MiniJson.writePretty(o);
    }

    private static List<Object> stepList(List<BuildRecord.Task> steps) {
        List<Object> out = new ArrayList<>(steps.size());
        for (BuildRecord.Task p : steps) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("stage", p.stage());
            pm.put("status", p.status());
            // Unknown duration (< 0) stays ABSENT on the wire: stamping it 0 made the SPA paint
            // genuinely-worked steps as dashed cache-skips — 0 means a true no-op (JK-1855).
            if (p.millis() >= 0) pm.put("millis", p.millis());
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

        BuildRecord.Tests tests = null;
        if (o.get("tests") instanceof Map<?, ?> tm) {
            Map<String, Object> t = (Map<String, Object>) tm;
            tests = new BuildRecord.Tests(lng(t, "total"), lng(t, "succeeded"), lng(t, "failed"), lng(t, "skipped"));
        }

        List<BuildRecord.Module> modules = new ArrayList<>();
        for (Object e : arr(o, "modules")) {
            Map<String, Object> mm = (Map<String, Object>) e;
            modules.add(new BuildRecord.Module(
                    str(mm, "coord"),
                    str(mm, "dir"),
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

        List<BuildRecord.Diag> diagnostics = new ArrayList<>();
        for (Object e : arr(o, "diagnostics")) {
            Map<String, Object> dm = (Map<String, Object>) e;
            diagnostics.add(new BuildRecord.Diag(
                    str(dm, "severity"),
                    str(dm, "dir"),
                    strOr(dm, "task", "step"),
                    str(dm, "code"),
                    str(dm, "message"),
                    str(dm, "test"),
                    str(dm, "exceptionClass"),
                    str(dm, "module"),
                    str(dm, "engine"),
                    firstStr(dm, "testClass", "class", "className"),
                    str(dm, "method"),
                    str(dm, "stack"),
                    str(dm, "file"),
                    (int) lng(dm, "line"),
                    (int) lng(dm, "snippetStart"),
                    strList(dm, "snippet"),
                    (int) lng(dm, "worker")));
        }

        return new BuildRecord(
                str(o, "id"),
                lng(o, "buildNumber"),
                (int) lng(o, "schema"),
                str(o, "kind"),
                str(o, "dir"),
                str(o, "coord"),
                str(o, "projectId"),
                lng(o, "startedAt"),
                lng(o, "finishedAt"),
                lng(o, "millis"),
                bool(o, "success"),
                bool(o, "cancelled"),
                (int) lng(o, "exitCode"),
                str(o, "jkVersion"),
                tests,
                modules,
                steps,
                diagnostics,
                str(o, "trigger"),
                str(o, "commit"),
                benefit,
                bool(o, "running"),
                io);
    }

    private static String str(Map<String, Object> o, String key) {
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

    /** First key present wins — dual-read for journal records written before the task/group rename. */
    private static String strOr(Map<String, Object> o, String key, String legacy) {
        String v = str(o, key);
        return v != null ? v : str(o, legacy);
    }

    private static String firstStr(Map<String, Object> o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (v != null) return v;
        }
        return null;
    }

    private static List<String> strList(Map<String, Object> o, String key) {
        if (!(o.get(key) instanceof List<?> l) || l.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object e : l) {
            if (e != null) out.add(String.valueOf(e));
        }
        return out;
    }

    /** Read a {@code "tasks"} array from a record or a module object ({@code "steps"} pre-rename). */
    @SuppressWarnings("unchecked")
    private static List<BuildRecord.Task> readSteps(Map<String, Object> o) {
        List<BuildRecord.Task> steps = new ArrayList<>();
        List<Object> rows = arr(o, "tasks");
        if (rows.isEmpty()) rows = arr(o, "steps");
        for (Object e : rows) {
            Map<String, Object> pm = (Map<String, Object>) e;
            // Journals on disk predate the rename; read the old keys so history stays readable.
            String stage = strOr(pm, "stage", "group");
            if (stage == null || stage.isBlank()) stage = str(pm, "phase");
            // Missing millis = unknown duration, kept as -1 — NOT 0, which is the true-no-op
            // signal the dashboard renders dashed (JK-1855).
            steps.add(new BuildRecord.Task(
                    str(pm, "name"),
                    stage,
                    str(pm, "status"),
                    pm.get("millis") instanceof Number n ? n.longValue() : -1L));
        }
        return steps;
    }
}
