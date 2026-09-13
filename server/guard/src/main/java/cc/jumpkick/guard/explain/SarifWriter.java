// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The machine view of the last guard run: {@code target/jk-guards.sarif} (SARIF 2.1.0, what GitHub
 * code scanning and IDEs consume unchanged) and {@code target/jk-guards.jsonl} (every violation row
 * across lanes). Both are rendered from what the lanes already left under {@code
 * target/jk-guards/} — the per-lane violation rows and the per-rule summaries — so a lane that
 * finishes re-renders the whole document in one pass and a cached lane leaves its last truth in place.
 *
 * <p>Mapping: one {@code result} per violation, {@code ruleId} the rule, {@code partialFingerprints}
 * our fingerprint, {@code baselineState} {@code new} or {@code unchanged}, a {@code suppression} with
 * the baseline entry's reason as {@code justification} for a baselined site; a red outcome that is not
 * a site ({@code blind}, {@code owner-missing}, …) is a result of kind {@code fail} without a
 * location; {@code scanner-failed} is a tool execution notification and makes {@code
 * invocations[0].executionSuccessful} false.
 */
public final class SarifWriter {

    public static final String SARIF_FILE = "jk-guards.sarif";
    public static final String JSONL_FILE = "jk-guards.jsonl";
    static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    static final String SRCROOT = "%SRCROOT%";
    static final String FINGERPRINT_KEY = "jk/fingerprint/v1";

    private SarifWriter() {}

    /** One violation row as a lane wrote it (see {@code GuardMessages.jsonl}). */
    public record Row(
            String code,
            String kind,
            boolean fresh,
            String file,
            int line,
            String at,
            String message,
            String instead,
            String why,
            String source) {}

    /** Render both files from the lanes' output; a workspace with no lane output writes neither. */
    public static void write(Path root, RuleSet rules, Baseline baseline) throws IOException {
        List<Row> rows = rows(root);
        List<RuleSummaries.Summary> summaries = RuleSummaries.read(root);
        Path target = root.resolve(BuildLayout.TARGET);
        if (rows.isEmpty() && summaries.isEmpty()) {
            Files.deleteIfExists(target.resolve(SARIF_FILE));
            Files.deleteIfExists(target.resolve(JSONL_FILE));
            return;
        }
        Files.createDirectories(target);
        AtomicWrites.replace(
                target.resolve(SARIF_FILE),
                MiniJson.writePretty(document(root, rules, baseline, rows, summaries)) + "\n");
        StringBuilder all = new StringBuilder();
        for (Row r : rows) all.append(rowJson(r)).append('\n');
        if (all.length() == 0) Files.deleteIfExists(target.resolve(JSONL_FILE));
        else AtomicWrites.replace(target.resolve(JSONL_FILE), all.toString());
    }

    /** Every lane's violation rows, lane files in name order. */
    public static List<Row> rows(Path root) throws IOException {
        Path dir = RuleSummaries.dir(root);
        List<Row> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files = new ArrayList<>();
        PathUtil.forEachChild(dir, (p, attrs) -> {
            String n = p.getFileName().toString();
            if (attrs.isRegularFile() && n.endsWith(".jsonl") && !n.endsWith(RuleSummaries.SUFFIX)) files.add(p);
            return true;
        });
        files.sort(null);
        for (Path f : files) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Object node = MiniJson.parse(line);
                out.add(new Row(
                        str(node, "code"),
                        str(node, "kind"),
                        !"baselined".equals(str(node, "baseline")),
                        str(node, "file"),
                        MiniJson.get(node, "line") instanceof Number n ? n.intValue() : 0,
                        str(node, "at"),
                        str(node, "message"),
                        str(node, "instead"),
                        str(node, "why"),
                        str(node, "source")));
            }
        }
        return out;
    }

    private static String str(@Nullable Object node, String key) {
        Object v = MiniJson.get(node, key);
        return v == null ? "" : String.valueOf(v);
    }

    static String rowJson(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.code());
        m.put("kind", r.kind());
        m.put("baseline", r.fresh() ? "new" : "baselined");
        m.put("file", r.file());
        m.put("line", r.line());
        m.put("at", r.at());
        m.put("message", r.message());
        m.put("instead", r.instead());
        m.put("why", r.why());
        m.put("source", r.source());
        return MiniJson.write(m);
    }

    /** The SARIF document as nested maps and lists, in the order the file prints them. */
    public static Map<String, Object> document(
            Path root, RuleSet rules, Baseline baseline, List<Row> rows, List<RuleSummaries.Summary> summaries) {
        // rules: every TOML rule, then every code the lanes reported that the TOML does not declare (guard tests)
        Map<String, Integer> ruleIndex = new LinkedHashMap<>();
        List<Object> descriptors = new ArrayList<>();
        for (String id : rules.ids()) {
            Rule r = rules.rules().get(id);
            if (r == null) continue;
            ruleIndex.put(id, descriptors.size());
            descriptors.add(descriptor(
                    id, r.why(), r.instead(), r.kind().id(), r.source().render()));
        }
        Map<String, Row> firstRow = new LinkedHashMap<>();
        for (Row r : rows) firstRow.putIfAbsent(r.code(), r);
        for (Row r : rows) {
            if (ruleIndex.containsKey(r.code())) continue;
            ruleIndex.put(r.code(), descriptors.size());
            descriptors.add(
                    descriptor(r.code(), r.why(), r.instead().isEmpty() ? null : r.instead(), r.kind(), r.source()));
        }
        for (RuleSummaries.Summary s : summaries) {
            if (ruleIndex.containsKey(s.code())) continue;
            ruleIndex.put(s.code(), descriptors.size());
            descriptors.add(descriptor(s.code(), s.note().isEmpty() ? s.code() : s.note(), null, "test", ""));
        }
        List<Object> results = new ArrayList<>();
        for (Row r : rows) results.add(result(r, ruleIndex.get(r.code()), baseline));
        // red outcomes that are not sites, from the latest summary per rule and lane
        Map<String, RuleSummaries.Summary> latest = new TreeMap<>();
        for (RuleSummaries.Summary s : summaries) {
            String key = s.code() + "@" + s.lane();
            RuleSummaries.Summary have = latest.get(key);
            if (have == null || have.ts() <= s.ts()) latest.put(key, s);
        }
        List<Object> notifications = new ArrayList<>();
        boolean executionSuccessful = true;
        for (RuleSummaries.Summary s : latest.values()) {
            switch (s.outcome()) {
                case "clean", "violations" -> {}
                case "scanner-failed" -> {
                    executionSuccessful = false;
                    notifications.add(notification("error", s, ruleIndex, "the scanner failed"));
                }
                // a guard whose tool is not on this machine: a notice, not a result
                case "skipped" -> notifications.add(notification("note", s, ruleIndex, "skipped"));
                default -> {
                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("ruleId", s.code());
                    Integer idx = ruleIndex.get(s.code());
                    if (idx != null) res.put("ruleIndex", idx);
                    res.put("kind", "fail");
                    res.put("level", "error");
                    res.put("message", text(s.outcome() + (s.note().isEmpty() ? "" : ": " + s.note())));
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("lane", s.lane());
                    props.put("outcome", s.outcome());
                    res.put("properties", props);
                    results.add(res);
                }
            }
        }
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "jk guard");
        driver.put("informationUri", "https://jumpkick.cc");
        driver.put("rules", descriptors);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("driver", driver);
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("executionSuccessful", executionSuccessful);
        invocation.put("endTimeUtc", Instant.now().toString());
        if (!notifications.isEmpty()) invocation.put("toolExecutionNotifications", notifications);
        Map<String, Object> srcRoot = new LinkedHashMap<>();
        String uri = root.toAbsolutePath().normalize().toUri().toString();
        srcRoot.put("uri", uri.endsWith("/") ? uri : uri + "/");
        Map<String, Object> bases = new LinkedHashMap<>();
        bases.put(SRCROOT, srcRoot);
        Map<String, Object> automation = new LinkedHashMap<>();
        Path name = root.toAbsolutePath().normalize().getFileName();
        automation.put(
                "id",
                "jk-guard/" + (name == null ? "workspace" : name.toString()) + "/"
                        + Instant.now().toString());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", tool);
        run.put("invocations", List.of(invocation));
        run.put("originalUriBaseIds", bases);
        run.put("automationDetails", automation);
        run.put("results", results);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$schema", SCHEMA);
        doc.put("version", "2.1.0");
        doc.put("runs", List.of(run));
        return doc;
    }

    private static Map<String, Object> descriptor(
            String id, String why, @Nullable String instead, String kind, String source) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("name", id);
        d.put("shortDescription", text(why));
        d.put("fullDescription", text(why));
        if (instead != null && !instead.isEmpty()) d.put("help", text(instead));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", "error");
        d.put("defaultConfiguration", config);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", kind);
        if (!source.isEmpty()) props.put("source", source);
        d.put("properties", props);
        return d;
    }

    private static Map<String, Object> result(Row r, @Nullable Integer ruleIndex, Baseline baseline) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ruleId", r.code());
        if (ruleIndex != null) res.put("ruleIndex", ruleIndex);
        res.put("level", "error");
        res.put("message", text(r.message()));
        if (!r.file().isEmpty()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("uri", r.file());
            artifact.put("uriBaseId", SRCROOT);
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", artifact);
            if (r.line() > 0) {
                Map<String, Object> region = new LinkedHashMap<>();
                region.put("startLine", r.line());
                physical.put("region", region);
            }
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("physicalLocation", physical);
            res.put("locations", List.of(location));
        }
        Map<String, Object> fingerprints = new LinkedHashMap<>();
        fingerprints.put(FINGERPRINT_KEY, r.at());
        res.put("partialFingerprints", fingerprints);
        res.put("baselineState", r.fresh() ? "new" : "unchanged");
        if (!r.fresh()) {
            Map<String, Object> suppression = new LinkedHashMap<>();
            suppression.put("kind", "external");
            suppression.put("status", "accepted");
            suppression.put("justification", justification(baseline, r));
            res.put("suppressions", List.of(suppression));
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("kind", r.kind());
        if (!r.instead().isEmpty()) props.put("instead", r.instead());
        props.put("why", r.why());
        res.put("properties", props);
        return res;
    }

    /** The baseline entry's reason for a baselined site, by fingerprint or metric unit. */
    static String justification(Baseline baseline, Row r) {
        RuleBaseline rb = baseline.of(r.code());
        for (Entry e : rb.entries()) {
            String key = e instanceof Entry.Site s ? s.at() : e instanceof Entry.Metric m ? m.unit() : "";
            if (key.equals(r.at()) && !e.reason().isEmpty()) return e.reason();
        }
        return "accepted in jk-guards-baseline.toml";
    }

    /** A tool-execution notification about a rule that produced no sites: its lane, outcome and note. */
    private static Map<String, Object> notification(
            String level, RuleSummaries.Summary s, Map<String, Integer> ruleIndex, String fallback) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("level", level);
        n.put("message", text(s.code() + ": " + (s.note().isEmpty() ? fallback : s.note())));
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", s.code());
        Integer idx = ruleIndex.get(s.code());
        if (idx != null) ref.put("index", idx);
        n.put("associatedRule", ref);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lane", s.lane());
        props.put("outcome", s.outcome());
        n.put("properties", props);
        return n;
    }

    private static Map<String, Object> text(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", s);
        return m;
    }
}
