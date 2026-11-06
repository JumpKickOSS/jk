// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The graph's fragments as action-cache entries, shared by every checkout and branch on the machine,
 * under the keys {@link GraphCacheKeys} computes. A file's declarations are keyed by its path,
 * content hash and the extractor version; a class set's compiled facts by its module, its source set
 * and the keys of the compiles that produced it, which cover its sources and classpath, so a fact is
 * reused only where that compile would be. Each entry is a key record under {@code
 * actions/keys/} with one output, the fragment's compact JSON in the cache CAS, so {@code jk cache
 * clean}, the budget and the sweep treat it as any other entry: a read marks it used, from memory
 * or from disk, and the prune takes it in last-use order with a build's own outputs. Entries read
 * or written are kept in memory for the workspace until a refresh no longer needs them.
 */
final class FragmentCache {

    private final @Nullable Path keys;
    private final @Nullable Cas cas;
    private final Map<String, Fragment> declared = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Fragment>> compiled = new ConcurrentHashMap<>();

    /** When this cache last marked each entry read or wrote it, so a hit from memory stats nothing. */
    private final Map<String, Long> marked = new ConcurrentHashMap<>();

    private FragmentCache(@Nullable Path cacheRoot) {
        this.keys = cacheRoot == null ? null : ActionTree.KEYS.under(CacheTree.ACTIONS.under(cacheRoot));
        this.cas = cacheRoot == null ? null : JkStores.cacheCas(cacheRoot);
    }

    /** The cache under {@code cacheRoot}; {@code null} keeps entries in memory only. */
    static FragmentCache at(@Nullable Path cacheRoot) {
        return new FragmentCache(cacheRoot);
    }

    /** The key of a class set's compiled facts; {@code null} when no compile stamped the set, which is then never cached. */
    static @Nullable String classesKey(GraphBuild.ClassIndex c) {
        return GraphCacheKeys.classes(c.modulePrefix(), c.sourceSet(), c.compileKeys());
    }

    /** The declarations stored under {@code key} for {@code rel}, else {@code null}. */
    @Nullable
    Fragment declarations(String key, String rel) {
        Fragment held = declared.get(key);
        if (held != null) {
            markRead(key);
            return held;
        }
        Object tree = load(key);
        if (tree == null) return null;
        try {
            Fragment f = GraphJson.compactOf(tree, rel);
            declared.put(key, f);
            return f;
        } catch (RuntimeException unreadable) {
            Log.debug("graph: cached declarations of " + rel + " do not read", unreadable);
            return null;
        }
    }

    void putDeclarations(String key, String rel, String sha, String flavor, Fragment f) {
        declared.put(key, f);
        store(
                key,
                GraphCacheKeys.DECLARATIONS,
                Map.of("path", rel, "sha256", sha, "extractor", GraphCacheKeys.VERSION + flavor),
                MiniJson.write(GraphJson.compactTree(f, rel)));
    }

    /** A class set's compiled facts by source file, stored under {@code key}, else {@code null}. */
    @Nullable
    Map<String, Fragment> classes(String key) {
        Map<String, Fragment> held = compiled.get(key);
        if (held != null) {
            markRead(key);
            return held;
        }
        if (!(load(key) instanceof Map<?, ?> byFile)) return null;
        try {
            Map<String, Fragment> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : byFile.entrySet()) {
                String rel = String.valueOf(e.getKey());
                out.put(rel, GraphJson.compactOf(e.getValue(), rel));
            }
            compiled.put(key, out);
            return out;
        } catch (RuntimeException unreadable) {
            Log.debug("graph: cached compiled facts do not read", unreadable);
            return null;
        }
    }

    /** Stores {@code c}'s compiled facts by file under {@code key}, with its class count for an engine that skips its index. */
    void putClasses(String key, GraphBuild.ClassIndex c, Map<String, Fragment> byFile) {
        compiled.put(key, byFile);
        Map<String, Object> doc = new LinkedHashMap<>();
        byFile.forEach((rel, f) -> doc.put(rel, GraphJson.compactTree(f, rel)));
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("extractor", GraphCacheKeys.VERSION);
        inputs.put("set", c.sourceSet());
        inputs.put(GraphCacheKeys.CLASS_COUNT, Integer.toString(c.classes()));
        for (String k : new TreeSet<>(c.compileKeys())) inputs.put("compile:" + k, k);
        store(key, GraphCacheKeys.CLASSES, inputs, MiniJson.write(doc));
    }

    /** Drops from memory every entry a refresh no longer reads; the cache keeps them. */
    void retain(Set<String> declarationKeys, Set<String> classKeys) {
        declared.keySet().retainAll(declarationKeys);
        compiled.keySet().retainAll(classKeys);
        marked.keySet().removeIf(k -> !declarationKeys.contains(k) && !classKeys.contains(k));
    }

    /**
     * Refreshes {@code key}'s eviction age at most once per {@link ActionRecordText#READ_REFRESH},
     * however often a resident worker answers it from memory.
     */
    private void markRead(String key) {
        if (keys == null) return;
        long now = Clock.SYSTEM.millis();
        Long last = marked.get(key);
        if (last != null && now - last < ActionRecordText.READ_REFRESH.toMillis()) return;
        ActionRecordText.markRead(keys.resolve(key));
        marked.put(key, now);
    }

    private @Nullable Object load(String key) {
        if (keys == null || cas == null) return null;
        try {
            String record = Files.readString(keys.resolve(key), StandardCharsets.UTF_8);
            String sha = ActionRecordText.parse(record).outputs().get(GraphCacheKeys.OUTPUT);
            if (sha == null) return null;
            Object tree = MiniJson.parse(new String(cas.read(sha), StandardCharsets.UTF_8));
            markRead(key);
            return tree;
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: cache entry " + key + " does not read; extracting again", unreadable);
            return null;
        }
    }

    /** Best-effort: an entry that cannot be written costs the next checkout one extraction. */
    private void store(String key, String task, Map<String, String> inputs, String json) {
        if (keys == null || cas == null) return;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String sha = Hashing.sha256Hex(bytes);
            cas.put(bytes, sha);
            Files.createDirectories(keys);
            AtomicWrites.replace(
                    keys.resolve(key),
                    ActionRecordText.render(task, key, inputs, Map.of(GraphCacheKeys.OUTPUT, sha), Set.of()));
            marked.put(key, Clock.SYSTEM.millis());
        } catch (IOException | RuntimeException failed) {
            Log.debug("graph: cache entry " + key + " not written", failed);
        }
    }
}
