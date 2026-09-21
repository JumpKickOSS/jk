// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.eval.RuleReport;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One line per rule per lane run under {@code target/jk-guards/<lane>.rules.jsonl}: outcome,
 * population, fresh and baselined counts. It is what {@code jk guard explain} reports as the last
 * outcome, written whenever a lane actually evaluates (a cached verdict leaves the last real run
 * in place, which is the truth it stands for).
 */
public final class RuleSummaries {

    static final String SUFFIX = ".rules.jsonl";

    private RuleSummaries() {}

    /** One rule's last evaluation in one lane. */
    public record Summary(
            String code,
            String lane,
            String outcome,
            String population,
            int fresh,
            int baselined,
            long ts,
            @Nullable Boolean bite,
            String note) {}

    public static Path dir(Path root) {
        return root.resolve(BuildLayout.TARGET).resolve("jk-guards");
    }

    public static void write(Path root, String taskId, LaneRun.Result result) throws IOException {
        Path dir = dir(root);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (RuleReport r : result.reports()) {
            sb.append("{\"code\":").append(Jsonl.quote(r.id()));
            sb.append(",\"lane\":").append(Jsonl.quote(taskId));
            sb.append(",\"outcome\":")
                    .append(Jsonl.quote(
                            r.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-')));
            sb.append(",\"population\":")
                    .append(Jsonl.quote(population(r.evaluation().population())));
            sb.append(",\"fresh\":").append(r.fresh().size());
            sb.append(",\"baselined\":").append(r.baselined().size());
            sb.append(",\"bite\":").append(r.evaluation().bites());
            sb.append(",\"note\":").append(Jsonl.quote(r.note()));
            sb.append(",\"ts\":").append(now);
            sb.append("}\n");
        }
        Path file = file(root, taskId);
        if (sb.length() == 0) Files.deleteIfExists(file);
        else AtomicWrites.replace(file, sb.toString());
    }

    /** The summary file of lane {@code taskId}: {@code target/jk-guards/<lane>.rules.jsonl}. */
    public static Path file(Path root, String taskId) {
        return dir(root).resolve(taskId.replaceAll("[^A-Za-z0-9._-]", "_") + SUFFIX);
    }

    static String population(Map<String, Long> population) {
        StringBuilder sb = new StringBuilder();
        for (var e : new TreeMap<>(population).entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Every recorded summary, oldest file order; empty when no lane has run. */
    public static List<Summary> read(Path root) throws IOException {
        Path dir = dir(root);
        List<Summary> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files = new ArrayList<>();
        PathUtil.forEachChild(dir, (p, attrs) -> {
            if (attrs.isRegularFile() && p.getFileName().toString().endsWith(SUFFIX)) files.add(p);
            return true;
        });
        files.sort(null);
        for (Path f : files) {
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                Summary s = parse(line);
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    private static @Nullable Summary parse(String line) {
        String code = Jsonl.str(line, "code");
        String lane = Jsonl.str(line, "lane");
        String outcome = Jsonl.str(line, "outcome");
        if (code == null || lane == null || outcome == null) return null;
        String population = Jsonl.str(line, "population");
        String note = Jsonl.str(line, "note");
        return new Summary(
                code,
                lane,
                outcome,
                population == null ? "" : population,
                Jsonl.intValue(line, "fresh", 0),
                Jsonl.intValue(line, "baselined", 0),
                Jsonl.longValue(line, "ts", 0),
                Jsonl.has(line, "bite") ? Jsonl.bool(line, "bite", false) : null,
                note == null ? "" : note);
    }
}
