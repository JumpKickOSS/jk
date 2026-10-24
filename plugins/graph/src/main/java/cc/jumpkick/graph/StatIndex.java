// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every graphed file's {@link StatIdentity} and content hash at the last build, in {@code
 * files.tsv} beside the graph: small enough that "did anything change?" costs one walk and one
 * read. The header names the extractor version; a different one discards the index.
 */
final class StatIndex {

    static final String FILE = "files.tsv";

    record Stat(StatIdentity identity, String sha) {}

    private final Map<String, Stat> stats;

    private StatIndex(Map<String, Stat> stats) {
        this.stats = stats;
    }

    static StatIndex empty() {
        return new StatIndex(new HashMap<>());
    }

    static StatIndex open(Path graphDir) {
        Path file = graphDir.resolve(FILE);
        Map<String, Stat> stats = new HashMap<>();
        if (!Files.isRegularFile(file)) return new StatIndex(stats);
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty() || !lines.getFirst().equals(header())) return new StatIndex(stats);
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split("\t", 3);
                StatIdentity identity = f.length == 3 ? StatIdentity.decode(f[1]) : null;
                if (identity != null) stats.put(f[2], new Stat(identity, f[0]));
            }
        } catch (IOException unreadable) {
            stats.clear();
        }
        return new StatIndex(stats);
    }

    /** The recorded hash when {@code source}'s stat proves it unchanged, else {@code null}. */
    @Nullable
    String unchanged(GraphSources.Source source, long now) {
        Stat s = stats.get(source.rel());
        if (s == null || !s.identity().equals(source.identity())) return null;
        return Settled.at(source.identity(), now) ? s.sha() : null;
    }

    /** A copy a refresh mutates and publishes only once the graph it vouches for is written. */
    StatIndex copy() {
        return new StatIndex(new HashMap<>(stats));
    }

    /** Forget {@code rel}: a file that would not read is not vouched for. */
    void remove(String rel) {
        stats.remove(rel);
    }

    @Nullable
    String sha(String rel) {
        Stat s = stats.get(rel);
        return s == null ? null : s.sha();
    }

    int size() {
        return stats.size();
    }

    void put(GraphSources.Source source, String sha) {
        stats.put(source.rel(), new Stat(source.identity(), sha));
    }

    /** Writes the stats of {@code live} only, so deleted files leave the index. */
    void save(Path graphDir, List<GraphSources.Source> live) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(header());
        for (GraphSources.Source s : live) {
            Stat st = stats.get(s.rel());
            if (st != null) lines.add(st.sha() + "\t" + st.identity().encode() + "\t" + s.rel());
        }
        Files.createDirectories(graphDir);
        AtomicWrites.replace(graphDir.resolve(FILE), String.join("\n", lines) + "\n");
    }

    private static String header() {
        return "# jk graph files, identity 2, extractors " + Extractors.VERSION;
    }
}
