// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes {@code jk-guards-baseline.toml}. The writer is deterministic — sorted ids,
 * sorted entries, no timestamps — so an unchanged baseline is a byte-identical file and a diff shows
 * exactly the tightening.
 *
 * <pre>
 * [one-digest-surface]
 * population = { classes = 1266 }
 * scope-reason = "the second build definition left the tree"
 * [[one-digest-surface.entries]]
 * at     = "cc.jumpkick.publish.Gpg#sign([B)[B -> java.security.MessageDigest#getInstance(**)"
 * reason = "PGP needs SHA-1 by spec"
 * </pre>
 *
 * {@code scope-reason} (and {@code [id.scope-reasons]} by lane) is written by a freeze that accepted
 * a smaller population as the floor, with the reason the human gave.
 */
public final class BaselineFile {

    private BaselineFile() {}

    private record Memo(long size, FileTime mtime, Baseline baseline) {}

    private static final Map<Path, Memo> MEMO = new ConcurrentHashMap<>();

    /**
     * Read, memoised by size and modification time: thirty module lanes reconcile against the same
     * file at once, and a general TOML parser builds a tree object per character of every reason
     * string — the one thing the engine's heap could not afford. The format is the writer's own,
     * so the reader is the writer's inverse and nothing more.
     */
    public static Baseline read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Baseline.EMPTY;
        Path key = file.toAbsolutePath().normalize();
        BasicFileAttributes a = Files.readAttributes(key, BasicFileAttributes.class);
        Memo m = MEMO.get(key);
        if (m != null && m.size() == a.size() && m.mtime().equals(a.lastModifiedTime())) return m.baseline();
        Baseline parsed = parse(Files.readString(key, StandardCharsets.UTF_8), key);
        MEMO.put(key, new Memo(a.size(), a.lastModifiedTime(), parsed));
        return parsed;
    }

    /** Line-shaped: the only forms {@link #render} writes. Anything else is a hand edit, refused. */
    static Baseline parse(String text, @Nullable Path file) throws IOException {
        String name = file == null ? "baseline" : file.getFileName().toString();
        Map<String, RuleBaseline> rules = new TreeMap<>();
        Map<String, Map<String, Map<String, Long>>> pops = new TreeMap<>();
        Map<String, Map<String, String>> reasons = new TreeMap<>();
        Map<String, List<Entry>> entries = new TreeMap<>();
        String rule = null;
        boolean inLanes = false;
        boolean inReasons = false;
        // The entry under construction: rule id, in, at | unit, value, reason.
        String[] cur = null;
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[[") && line.endsWith(".entries]]")) {
                finish(cur, entries, name, lineNo);
                String id = line.substring(2, line.length() - ".entries]]".length());
                if (!id.equals(rule)) throw refuse(name, lineNo, "entries for `" + id + "` outside its table");
                cur = new String[] {id, "", null, null, null, null};
                inLanes = false;
                inReasons = false;
                continue;
            }
            if (line.startsWith("[") && line.endsWith(".populations]")) {
                finish(cur, entries, name, lineNo);
                cur = null;
                String id = line.substring(1, line.length() - ".populations]".length());
                if (!id.equals(rule)) throw refuse(name, lineNo, "populations for `" + id + "` outside its table");
                inLanes = true;
                inReasons = false;
                continue;
            }
            if (line.startsWith("[") && line.endsWith(".scope-reasons]")) {
                finish(cur, entries, name, lineNo);
                cur = null;
                String id = line.substring(1, line.length() - ".scope-reasons]".length());
                if (!id.equals(rule)) throw refuse(name, lineNo, "scope-reasons for `" + id + "` outside its table");
                inLanes = false;
                inReasons = true;
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]") && !line.startsWith("[[")) {
                finish(cur, entries, name, lineNo);
                cur = null;
                inLanes = false;
                inReasons = false;
                rule = line.substring(1, line.length() - 1);
                if (rule.isEmpty() || rules.containsKey(rule) || pops.containsKey(rule) || entries.containsKey(rule)) {
                    throw refuse(name, lineNo, "rule table `" + rule + "` is empty or repeated");
                }
                pops.put(rule, new TreeMap<>());
                reasons.put(rule, new TreeMap<>());
                entries.put(rule, new ArrayList<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || rule == null) throw refuse(name, lineNo, "expected `key = value`");
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (cur != null) {
                switch (key) {
                    case "in" -> cur[1] = string(value, name, lineNo);
                    case "at" -> cur[2] = string(value, name, lineNo);
                    case "unit" -> cur[3] = string(value, name, lineNo);
                    case "value" -> cur[4] = value;
                    case "reason" -> cur[5] = string(value, name, lineNo);
                    default -> throw refuse(name, lineNo, "unknown entry key `" + key + "`");
                }
                continue;
            }
            Map<String, Map<String, Long>> lanes = Objects.requireNonNull(pops.get(rule), "pops");
            Map<String, String> laneReasons = Objects.requireNonNull(reasons.get(rule), "reasons");
            if (inLanes) {
                lanes.put(string(key, name, lineNo), counts(value, name, lineNo));
            } else if (inReasons) {
                laneReasons.put(string(key, name, lineNo), string(value, name, lineNo));
            } else if (key.equals("population")) {
                lanes.put("", counts(value, name, lineNo));
            } else if (key.equals("scope-reason")) {
                laneReasons.put("", string(value, name, lineNo));
            } else {
                throw refuse(name, lineNo, "unknown key `" + key + "`");
            }
        }
        finish(cur, entries, name, lineNo);
        for (var e : pops.entrySet()) {
            rules.put(
                    e.getKey(),
                    new RuleBaseline(
                            e.getValue(),
                            entries.getOrDefault(e.getKey(), List.of()),
                            reasons.getOrDefault(e.getKey(), Map.of())));
        }
        return new Baseline(rules);
    }

    private static void finish(String @Nullable [] cur, Map<String, List<Entry>> entries, String name, int lineNo)
            throws IOException {
        if (cur == null) return;
        String reason = cur[5] == null ? "" : cur[5];
        String in = cur[1] == null ? "" : cur[1];
        List<Entry> list = entries.computeIfAbsent(cur[0], k -> new ArrayList<>());
        if (cur[2] != null) {
            list.add(new Entry.Site(cur[2], reason, in));
        } else if (cur[3] != null) {
            double v;
            try {
                v = cur[4] == null ? 0 : Double.parseDouble(cur[4]);
            } catch (NumberFormatException e) {
                throw refuse(name, lineNo, "entry value `" + cur[4] + "` is not a number");
            }
            list.add(new Entry.Metric(cur[3], v, reason, in));
        } else {
            throw refuse(name, lineNo, "an entry of `" + cur[0] + "` has neither `at` nor `unit`");
        }
    }

    /** {@code { classes = 1266, sites = 3 }} → counts. */
    private static Map<String, Long> counts(String value, String name, int lineNo) throws IOException {
        Map<String, Long> out = new TreeMap<>();
        String body = value.strip();
        if (!body.startsWith("{") || !body.endsWith("}")) throw refuse(name, lineNo, "expected an inline table");
        body = body.substring(1, body.length() - 1).strip();
        if (body.isEmpty()) return out;
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) throw refuse(name, lineNo, "expected `unit = count` in the population");
            try {
                out.put(
                        pair.substring(0, eq).strip(),
                        Long.parseLong(pair.substring(eq + 1).strip()));
            } catch (NumberFormatException e) {
                throw refuse(name, lineNo, "population count `" + pair.strip() + "` is not a number");
            }
        }
        return out;
    }

    private static String string(String value, String name, int lineNo) throws IOException {
        String v = value.strip();
        if (v.length() < 2 || !v.startsWith("\"") || !v.endsWith("\"")) {
            throw refuse(name, lineNo, "expected a quoted string, got `" + value + "`");
        }
        return MinimalToml.unquote(v);
    }

    private static IOException refuse(String name, int lineNo, String what) {
        return new IOException(
                name + ":" + lineNo + ": " + what
                        + " — never edit the baseline by hand; delete it and let the engine rewrite it, or run jk guard freeze");
    }

    public static String render(Baseline b) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Engine-owned. Tightened on every jk build; grown only by `jk guard freeze <id> --reason`.\n");
        sb.append(
                "# Never edit by hand: an entry without a reason is a suppression, and there is no suppression syntax.\n");
        for (var e : b.rules().entrySet()) {
            String id = e.getKey();
            RuleBaseline rb = e.getValue();
            sb.append('\n').append('[').append(id).append("]\n");
            if (!rb.population().isEmpty())
                sb.append("population = ").append(inline(rb.population())).append('\n');
            String wholeReason = rb.scopeReason("");
            if (wholeReason != null)
                sb.append("scope-reason = ")
                        .append(MinimalToml.quote(wholeReason))
                        .append('\n');
            boolean lanes = false;
            for (var p : rb.populations().entrySet()) {
                if (p.getKey().isEmpty()) continue;
                if (!lanes) sb.append('[').append(id).append(".populations]\n");
                lanes = true;
                sb.append(MinimalToml.quote(p.getKey()))
                        .append(" = ")
                        .append(inline(p.getValue()))
                        .append('\n');
            }
            boolean reasons = false;
            for (var r : rb.scopeReasons().entrySet()) {
                if (r.getKey().isEmpty()) continue;
                if (!reasons) sb.append('[').append(id).append(".scope-reasons]\n");
                reasons = true;
                sb.append(MinimalToml.quote(r.getKey()))
                        .append(" = ")
                        .append(MinimalToml.quote(r.getValue()))
                        .append('\n');
            }
            for (Entry en : rb.entries()) {
                sb.append("[[").append(id).append(".entries]]\n");
                if (!en.in().isEmpty())
                    sb.append("in     = ").append(MinimalToml.quote(en.in())).append('\n');
                if (en instanceof Entry.Site s) {
                    sb.append("at     = ").append(MinimalToml.quote(s.at())).append('\n');
                } else if (en instanceof Entry.Metric m) {
                    sb.append("unit   = ").append(MinimalToml.quote(m.unit())).append('\n');
                    sb.append("value  = ").append(number(m.value())).append('\n');
                }
                sb.append("reason = ").append(MinimalToml.quote(en.reason())).append('\n');
            }
        }
        return sb.toString();
    }

    private static String inline(Map<String, Long> counts) {
        StringBuilder sb = new StringBuilder("{ ");
        boolean first = true;
        for (var p : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(p.getKey()).append(" = ").append(p.getValue());
        }
        return sb.append(" }").toString();
    }

    /** Write, or delete the file when the baseline is empty — an empty file is noise in a diff. */
    public static void write(Path file, Baseline b) throws IOException {
        if (b.rules().isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        AtomicWrites.replace(file, render(b));
    }

    private static String number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }
}
