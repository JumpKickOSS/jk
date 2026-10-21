// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Every file's last fragment keyed by the file's content hash and path, so a file whose bytes did
 * not change is never extracted again. One JSONL file beside the graph, read only when the graph
 * has to be linked again; the header names the extractor version and a different one discards
 * the store.
 */
final class FragmentStore {

    static final String FILE = "fragments.jsonl";

    private final Map<String, Fragment> fragments;

    private FragmentStore(Map<String, Fragment> fragments) {
        this.fragments = fragments;
    }

    static FragmentStore empty() {
        return new FragmentStore(new ConcurrentHashMap<>());
    }

    static FragmentStore open(Path graphDir) {
        Path file = graphDir.resolve(FILE);
        Map<String, Fragment> fragments = new ConcurrentHashMap<>();
        if (!Files.isRegularFile(file)) return new FragmentStore(fragments);
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null || !Extractors.VERSION.equals(MiniJson.str(MiniJson.parse(header), "extractors"))) {
                return new FragmentStore(fragments);
            }
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                Object row = MiniJson.parse(line);
                String key = MiniJson.str(row, "key");
                if (key != null) fragments.put(key, GraphJson.fragmentOf(MiniJson.get(row, "fragment")));
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            Log.debug("graph: fragment store unreadable; extracting every file", unreadable);
            fragments.clear();
        }
        return new FragmentStore(fragments);
    }

    /** The key a file's fragment is stored under: the same bytes at another path are another fragment. */
    static String key(String rel, String sha) {
        return sha + ":" + rel;
    }

    @Nullable
    Fragment get(String key) {
        return fragments.get(key);
    }

    void put(String key, Fragment fragment) {
        fragments.put(key, fragment);
    }

    /** Writes the fragments under {@code live} keys only, so deleted and replaced versions leave. */
    void save(Path graphDir, Collection<String> live) throws IOException {
        Files.createDirectories(graphDir);
        Path tmp = AtomicWrites.staging(graphDir.resolve(FILE));
        try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            out.write(MiniJson.write(Map.of("extractors", Extractors.VERSION)));
            out.newLine();
            for (String key : live) {
                Fragment f = fragments.get(key);
                if (f == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", key);
                row.put("fragment", GraphJson.fragmentTree(f));
                out.write(MiniJson.write(row));
                out.newLine();
            }
        }
        AtomicWrites.moveInto(tmp, graphDir.resolve(FILE));
    }
}
