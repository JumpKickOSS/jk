// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Every file's last fragment, keyed by path and checked by size, modification time and content
 * hash: a file whose stat is unchanged is not read at all, one whose bytes are unchanged is not
 * extracted again. One JSONL file under {@code target/graph/}: a header naming the extractor
 * version (a different version discards the store), then one row per file.
 */
final class FragmentStore {

    static final String FILE = "fragments.jsonl";

    /** A file touched this recently may still be mid-write within the same mtime tick: always hash it. */
    private static final long RACY_MILLIS = 2_000;

    record Entry(long size, long mtimeMillis, String sha, Fragment fragment) {}

    private final Path file;
    private final Map<String, Entry> entries;

    private FragmentStore(Path file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    static FragmentStore open(Path graphDir) {
        Path file = graphDir.resolve(FILE);
        Map<String, Entry> entries = new ConcurrentHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                List<String> lines = Files.readAllLines(file);
                if (!lines.isEmpty() && Extractors.VERSION.equals(Jsonl.str(lines.getFirst(), "extractors"))) {
                    for (String line : lines.subList(1, lines.size())) {
                        Object row = MiniJson.parse(line);
                        String path = MiniJson.str(row, "path");
                        String sha = MiniJson.str(row, "sha");
                        if (path == null || sha == null) continue;
                        entries.put(
                                path,
                                new Entry(
                                        number(row, "size"),
                                        number(row, "mtime"),
                                        sha,
                                        GraphJson.fragmentOf(MiniJson.get(row, "fragment"))));
                    }
                }
            } catch (IOException | IllegalArgumentException unreadable) {
                Log.debug("graph: fragment store unreadable; extracting every file", unreadable);
                entries.clear();
            }
        }
        return new FragmentStore(file, entries);
    }

    /** The stored fragment when {@code source}'s stat proves it unchanged, else {@code null}. */
    @Nullable
    Fragment byStat(GraphSources.Source source, long now) {
        Entry e = entries.get(source.rel());
        if (e == null || e.size() != source.size() || e.mtimeMillis() != source.mtimeMillis()) return null;
        return now - source.mtimeMillis() < RACY_MILLIS ? null : e.fragment();
    }

    /** The stored fragment when the file's bytes hash to {@code sha}, else {@code null}. */
    @Nullable
    Fragment bySha(String rel, String sha) {
        Entry e = entries.get(rel);
        return e != null && e.sha().equals(sha) ? e.fragment() : null;
    }

    @Nullable
    String sha(String rel) {
        Entry e = entries.get(rel);
        return e == null ? null : e.sha();
    }

    private static long number(@Nullable Object row, String key) {
        return MiniJson.get(row, key) instanceof Number n ? n.longValue() : -1;
    }

    void put(GraphSources.Source source, String sha, Fragment fragment) {
        entries.put(source.rel(), new Entry(source.size(), source.mtimeMillis(), sha, fragment));
    }

    /** Writes the entries for {@code live} only, so deleted files leave the store. */
    void save(List<GraphSources.Source> live) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(MiniJson.write(Map.of("extractors", Extractors.VERSION)));
        for (GraphSources.Source s : live) {
            Entry e = entries.get(s.rel());
            if (e == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", s.rel());
            row.put("size", e.size());
            row.put("mtime", e.mtimeMillis());
            row.put("sha", e.sha());
            row.put("fragment", GraphJson.fragmentTree(e.fragment()));
            lines.add(MiniJson.write(row));
        }
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, String.join("\n", lines) + "\n");
    }
}
