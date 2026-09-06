// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.baseline;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Reads and writes {@code jk-guards-baseline.toml}. The writer is deterministic — sorted ids,
 * sorted entries, no timestamps — so an unchanged baseline is a byte-identical file and a diff shows
 * exactly the tightening.
 *
 * <pre>
 * [one-digest-surface]
 * population = { classes = 1266 }
 * [[one-digest-surface.entries]]
 * at     = "cc.jumpkick.publish.Gpg#sign([B)[B -> java.security.MessageDigest#getInstance(**)"
 * reason = "PGP needs SHA-1 by spec"
 * </pre>
 */
public final class BaselineFile {

    private BaselineFile() {}

    public static Baseline read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Baseline.EMPTY;
        return parse(Files.readString(file, StandardCharsets.UTF_8), file);
    }

    static Baseline parse(String text, @Nullable Path file) throws IOException {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw new IOException(
                    (file == null ? "baseline" : file.getFileName()) + ": "
                            + toml.errors().get(0).getMessage()
                            + " — never edit the baseline by hand; delete it and let the engine rewrite it, or run jk guard freeze");
        }
        Map<String, RuleBaseline> rules = new TreeMap<>();
        for (String id : toml.keySet()) {
            TomlTable t = toml.getTable(id);
            if (t == null) throw new IOException("baseline: `" + id + "` is not a rule table");
            Map<String, Long> population = new TreeMap<>();
            TomlTable pop = t.getTable("population");
            if (pop != null) {
                for (String unit : pop.keySet()) {
                    Long n = pop.getLong(unit);
                    if (n != null) population.put(unit, n);
                }
            }
            List<Entry> entries = new ArrayList<>();
            TomlArray arr = t.getArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    TomlTable e = arr.getTable(i);
                    String reason = e.isString("reason") ? e.getString("reason") : "";
                    if (reason == null) reason = "";
                    if (e.isString("at")) {
                        entries.add(new Entry.Site(String.valueOf(e.getString("at")), reason));
                    } else if (e.isString("unit")) {
                        double v = e.isDouble("value")
                                ? valueOf(e.getDouble("value"))
                                : e.isLong("value") ? valueOf(e.getLong("value")) : 0;
                        entries.add(new Entry.Metric(String.valueOf(e.getString("unit")), v, reason));
                    } else {
                        throw new IOException(
                                "baseline: entry " + (i + 1) + " of `" + id + "` has neither `at` nor `unit`");
                    }
                }
            }
            rules.put(id, new RuleBaseline(population, entries));
        }
        return new Baseline(rules);
    }

    private static double valueOf(@Nullable Number n) {
        return n == null ? 0 : n.doubleValue();
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
            if (!rb.population().isEmpty()) {
                sb.append("population = { ");
                boolean first = true;
                for (var p : rb.population().entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(p.getKey()).append(" = ").append(p.getValue());
                }
                sb.append(" }\n");
            }
            List<Entry> sorted = new ArrayList<>(rb.entries());
            sorted.sort((x, y) -> x.key().compareTo(y.key()));
            for (Entry en : sorted) {
                sb.append("[[").append(id).append(".entries]]\n");
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
