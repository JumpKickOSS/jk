// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.TrimRegistry;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * What one scan found in one corpus file, kept across lane runs in this process so an unedited file
 * is not scanned again. An entry is filed under a generation, the digest of everything the scan
 * reads besides the file (the rules, the root, the module map), and the file's {@link StatIdentity}
 * at the walk; it is handed back only while both still match. As in the engine's content-hash memo,
 * a file is recorded only once it is {@link Settled} and still has the identity it was read under,
 * so a rewrite inside one timestamp tick is scanned again.
 *
 * <p>Bounded at {@link #MAX_ENTRIES}: past it, entries of other generations go first, then
 * everything. It joins the {@link TrimRegistry}, so the engine's idle and heap-pressure trims empty
 * it too. Losing an entry costs one scan.
 *
 * @param <V> one file's findings; must not change once recorded
 */
final class ScanMemo<V> implements TrimRegistry.Trimmable {

    /** Entries kept at most: a few workspaces' corpora, at up to a kilobyte each with their findings. */
    static final int MAX_ENTRIES = 32_768;

    private record Key(String generation, Path file) {}

    private record Cached<V>(StatIdentity identity, V findings) {}

    private final Map<Key, Cached<V>> entries = new ConcurrentHashMap<>();

    ScanMemo() {
        TrimRegistry.join(this);
    }

    /** The findings recorded for {@code f} under {@code generation}, or {@code null} when it must be scanned. */
    @Nullable
    V get(String generation, TextFiles.Entry f) {
        Cached<V> c = entries.get(new Key(generation, f.file()));
        return c != null && c.identity().equals(f.identity()) ? c.findings() : null;
    }

    /** Record what a scan of {@code f} found, if the file is settled and unchanged since the walk. */
    void put(String generation, TextFiles.Entry f, V findings) throws IOException {
        if (!Settled.is(f.identity()) || !f.identity().equals(StatIdentity.of(f.file()))) return;
        if (entries.size() >= MAX_ENTRIES) {
            entries.keySet().removeIf(k -> !k.generation().equals(generation));
            if (entries.size() >= MAX_ENTRIES) entries.clear();
        }
        entries.put(new Key(generation, f.file()), new Cached<>(f.identity(), findings));
    }

    /** How many files are recorded. */
    int size() {
        return entries.size();
    }

    /** Forget every entry. */
    void clear() {
        entries.clear();
    }

    @Override
    public int trimOutside(@Nullable Path keep) {
        int before = entries.size();
        if (keep == null) entries.clear();
        else entries.keySet().removeIf(k -> !k.file().startsWith(keep));
        return Math.max(0, before - entries.size());
    }
}
