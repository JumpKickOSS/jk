// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The graph a project commits, under {@code .jk/graph/}: every file's fragment, and the facts its
 * compiled classes gave, keyed by the file's content hash and path. One {@code graph.jsonl} per
 * module, at the module's own path below {@code .jk/graph/}, one line per file in path order, so a
 * change to one file changes one line and two branches merge line by line ({@code merge=union} in
 * the {@code .gitattributes} written beside them: the line whose hash matches the tree is used, and
 * the next write drops the other). A file whose hash matches is never extracted again, on this
 * machine or on any other that checked the project out; a line another extractor version wrote
 * reads as no line.
 */
final class FragmentStore {

    /** Each module's file of lines, at the module's path under the store's directory. */
    static final String FILE = "graph.jsonl";

    /** The line that opens every file: what it is, for a reader who opens one. */
    static final String HEADER = MiniJson.write(Map.of("jk-graph", 1));

    static final String ATTRIBUTES = ".gitattributes";

    /** Union merges, no text diffs of long lines, and folded away in a review. */
    static final String ATTRIBUTES_TEXT = "graph.jsonl merge=union -diff linguist-generated=true\n";

    /**
     * Kept out of searches: ripgrep, and the agents' search tools built on it, skip what a {@code
     * .ignore} names even when told to search hidden files, so a search for a name never returns
     * these lines.
     */
    static final String IGNORE = ".ignore";

    static final String IGNORE_TEXT = FILE + "\n";

    /** The hex digits of a content hash a key holds: enough to tell versions of one file apart. */
    private static final int SHA_CHARS = 16;

    private final Map<String, Fragment> fragments;
    private final Map<String, Fragment> classes;

    private FragmentStore(Map<String, Fragment> fragments, Map<String, Fragment> classes) {
        this.fragments = fragments;
        this.classes = classes;
    }

    static FragmentStore empty() {
        return new FragmentStore(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /** Every module file under {@code dir}; a line that does not read is skipped, not the file. */
    static FragmentStore open(Path dir) {
        FragmentStore store = empty();
        for (Path file : files(dir)) {
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    store.read(line);
                }
            } catch (IOException unreadable) {
                Log.debug("graph: cannot read " + file + "; extracting its files again", unreadable);
            }
        }
        return store;
    }

    private void read(String line) {
        if (line.isEmpty() || line.charAt(0) != '{' || line.equals(HEADER)) return;
        try {
            Object row = MiniJson.parse(line);
            String path = MiniJson.str(row, "p");
            String sha = MiniJson.str(row, "s");
            if (path == null || sha == null || !Extractors.VERSION.equals(MiniJson.str(row, "x"))) return;
            Object source = MiniJson.get(row, "f");
            fragments.put(sha + ":" + path, source == null ? Fragment.EMPTY : GraphJson.compactOf(source, path));
            Object compiled = MiniJson.get(row, "c");
            if (compiled != null) classes.put(classKey(path, sha), GraphJson.compactOf(compiled, path));
        } catch (RuntimeException unreadable) {
            Log.debug("graph: skipping a stored line that does not read", unreadable);
        }
    }

    /** The module files under {@code dir}, in path order. */
    static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                if (file.getFileName().toString().equals(FILE)) out.add(file);
            });
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: cannot list " + dir, unreadable);
            return List.of();
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    /**
     * Every module file's path and stat under {@code dir}: what changes when a file is written here
     * or arrives with a checkout, so a graph linked from older lines is linked again.
     */
    static String stamp(Path dir) {
        StringBuilder out = new StringBuilder();
        for (Path file : files(dir)) {
            out.append(dir.relativize(file)).append('\t');
            try {
                out.append(StatIdentity.of(file).encode());
            } catch (IOException gone) {
                out.append('-');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The key a file's fragment is stored under: the leading digits of its content hash, then
     * {@code flavor} (which reader made it), then the path. The same bytes at another path are
     * another fragment.
     */
    static String key(String rel, String sha, String flavor) {
        return shortSha(sha) + flavor + ":" + rel;
    }

    /** The key the compiled facts of {@code rel} are stored under, from its content hash or a fragment key's hash part. */
    static String classKey(String rel, String sha) {
        int flavor = sha.indexOf('+');
        return shortSha(flavor < 0 ? sha : sha.substring(0, flavor)) + ":" + rel;
    }

    private static String shortSha(String sha) {
        return sha.length() > SHA_CHARS ? sha.substring(0, SHA_CHARS) : sha;
    }

    @Nullable
    Fragment get(String key) {
        return fragments.get(key);
    }

    void put(String key, Fragment fragment) {
        fragments.put(key, fragment);
    }

    /** The compiled facts stored for {@code rel} at content hash {@code sha}, else {@code null}. */
    @Nullable
    Fragment classes(String rel, String sha) {
        return classes.get(classKey(rel, sha));
    }

    /** One file's stored state: its fragment's key, and the compiled facts of the same bytes when known. */
    record Row(String rel, String key, @Nullable Fragment classes) {}

    /**
     * Writes {@code live}, one line per file, each into its module's file; drops every other
     * fragment from memory, and every module file under {@code dir} that holds none of them. A
     * file whose bytes would not change is not written, so an unchanged tree leaves the checkout
     * clean.
     */
    void save(Path dir, List<Row> live, Modules modules) throws IOException {
        Set<String> keys = new HashSet<>();
        Set<String> classKeys = new HashSet<>();
        Map<String, StringBuilder> byModule = new TreeMap<>();
        byModule.put("", new StringBuilder());
        List<Row> sorted = new ArrayList<>(live);
        sorted.sort(Comparator.comparing(Row::rel));
        for (Row r : sorted) {
            Fragment f = fragments.get(r.key());
            if (f == null) continue;
            keys.add(r.key());
            String sha = r.key().substring(0, r.key().indexOf(':'));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("p", r.rel());
            row.put("s", sha);
            row.put("x", Extractors.VERSION);
            if (!f.nodes().isEmpty() || !f.edges().isEmpty() || !f.mentions().isEmpty()) {
                row.put("f", GraphJson.compactTree(f, r.rel()));
            }
            Fragment compiled = r.classes();
            if (compiled != null) {
                classKeys.add(classKey(r.rel(), sha));
                classes.put(classKey(r.rel(), sha), compiled);
                row.put("c", GraphJson.compactTree(compiled, r.rel()));
            }
            Modules.Module m = modules.of(r.rel());
            byModule.computeIfAbsent(m == null ? "" : m.prefix(), k -> new StringBuilder())
                    .append(MiniJson.write(row))
                    .append('\n');
        }
        fragments.keySet().retainAll(keys);
        classes.keySet().retainAll(classKeys);
        Files.createDirectories(dir);
        Set<Path> written = new HashSet<>();
        for (Map.Entry<String, StringBuilder> m : byModule.entrySet()) {
            Path file = dir.resolve(m.getKey()).resolve(FILE).normalize();
            written.add(file);
            Files.createDirectories(file.getParent());
            AtomicWrites.replaceIfChanged(file, HEADER + "\n" + m.getValue());
        }
        for (Path old : files(dir)) {
            if (!written.contains(old.normalize())) Files.deleteIfExists(old);
        }
        AtomicWrites.replaceIfChanged(dir.resolve(ATTRIBUTES), ATTRIBUTES_TEXT);
        AtomicWrites.replaceIfChanged(dir.resolve(IGNORE), IGNORE_TEXT);
    }
}
