// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.task.ClassAbi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Domain result for {@code --affected} test ranking. CLI JSONL, MCP, and markdown are views. */
public record AffectedTests(
        List<ModuleRow> modules,
        List<ChangedType> changed,
        List<Row> ranked,
        int cap,
        int candidateCount,
        @Nullable Refuse refuse) {

    public static final int CAP = 20;
    public static final int MAX_CHANGED_FQCS = 40;
    public static final int MAX_CHANGED_MODULES = 12;
    public static final int MAX_DIRTY_PATHS = 200;

    public AffectedTests {
        modules = modules == null ? List.of() : List.copyOf(modules);
        changed = changed == null ? List.of() : List.copyOf(changed);
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
    }

    public record ModuleRow(String path, String coord, String why) {}

    public record ChangedType(String module, String fqcn, ClassAbi.Kind kind) {}

    public record Row(String module, String className, String reason, int score) {}

    public record Refuse(String code, @Nullable String message) {}

    public static AffectedTests ranked(
            List<ModuleRow> modules, List<ChangedType> changed, List<Row> ranked, int candidates) {
        return new AffectedTests(modules, changed, ranked, CAP, candidates, null);
    }

    public static AffectedTests refused(Refuse refuse, List<ModuleRow> modules, List<ChangedType> changed) {
        return new AffectedTests(modules, changed, List.of(), CAP, 0, refuse);
    }

    public static AffectedTests empty(List<ModuleRow> modules, List<ChangedType> changed) {
        return new AffectedTests(modules, changed, List.of(), CAP, 0, null);
    }

    public boolean refused() {
        return refuse != null;
    }

    public List<String> classNames() {
        List<String> out = new ArrayList<>(ranked.size());
        for (Row r : ranked) out.add(r.className());
        return List.copyOf(out);
    }

    /** Stamp extra; empty when refuse or empty ranking. */
    public String identityToken() {
        if (refuse != null || ranked.isEmpty()) return "";
        List<String> names = new ArrayList<>(classNames());
        names.sort(String::compareTo);
        return Integer.toHexString(String.join("\0", names).hashCode());
    }

    public AffectedTests merge(AffectedTests other) {
        if (other == null) return this;
        LinkedHashMap<String, ModuleRow> mods = new LinkedHashMap<>();
        for (ModuleRow m : modules) mods.put(m.path(), m);
        for (ModuleRow m : other.modules) mods.putIfAbsent(m.path(), m);
        List<ChangedType> ch = new ArrayList<>(changed);
        ch.addAll(other.changed);
        List<Row> rows = new ArrayList<>(ranked);
        rows.addAll(other.ranked);
        Refuse r = refuse != null ? refuse : other.refuse;
        return new AffectedTests(
                List.copyOf(mods.values()),
                List.copyOf(ch),
                List.copyOf(rows),
                CAP,
                candidateCount + other.candidateCount,
                r);
    }

    public Map<String, Object> toStructured() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cap", cap);
        m.put("candidateCount", candidateCount);
        m.put("modules", modules.stream().map(AffectedTests::moduleMap).toList());
        m.put("changed", changed.stream().map(AffectedTests::changedMap).toList());
        m.put("ranked", ranked.stream().map(AffectedTests::rowMap).toList());
        if (ranked.size() < candidateCount) m.put("truncated", true);
        if (refuse != null) {
            m.put("error", refuse.message());
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("code", refuse.code());
            rf.put("message", refuse.message());
            m.put("refuse", rf);
        }
        return m;
    }

    private static Map<String, Object> moduleMap(ModuleRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", r.path());
        m.put("coord", r.coord());
        m.put("why", r.why());
        return m;
    }

    private static Map<String, Object> changedMap(ChangedType c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", c.module());
        m.put("fqcn", c.fqcn());
        m.put("kind", c.kind().name());
        return m;
    }

    private static Map<String, Object> rowMap(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", r.module());
        m.put("class", r.className());
        m.put("reason", r.reason());
        m.put("score", r.score());
        return m;
    }
}
