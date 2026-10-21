// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a served graph worker keeps between runs: one workspace's stat index, fragment store and
 * graph, each with the size and modification time of the file it was read from or written to. A
 * piece is reused only while its file still has that stamp, so a graph another process wrote is
 * read again. A run for another workspace drops everything first.
 */
final class GraphMemory {

    private GraphMemory() {}

    /** A file's size and modification time; {@code null} when the file is missing. */
    record Stamp(long size, long mtimeMillis) {
        static @Nullable Stamp of(Path file) {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                return new Stamp(a.size(), a.lastModifiedTime().toMillis());
            } catch (IOException missing) {
                return null;
            }
        }
    }

    /** A stored graph with the key it was built under. */
    record Graph(CodeGraph graph, GraphSnapshot.Key key) {}

    private static @Nullable Path dir;
    private static @Nullable StatIndex stats;
    private static @Nullable Stamp statsStamp;
    private static @Nullable FragmentStore store;
    private static @Nullable Stamp storeStamp;
    private static @Nullable Graph graph;
    private static @Nullable Stamp graphStamp;

    /** The stat index of {@code out}: the one in memory while {@code files.tsv} is unchanged. */
    static synchronized StatIndex stats(Path out) {
        own(out);
        Stamp now = Stamp.of(out.resolve(StatIndex.FILE));
        if (stats == null || now == null || !now.equals(statsStamp)) {
            stats = StatIndex.open(out);
            statsStamp = now;
        }
        return stats;
    }

    /** {@code index} was just saved to {@code out}. */
    static synchronized void savedStats(Path out, StatIndex index) {
        own(out);
        stats = index;
        statsStamp = Stamp.of(out.resolve(StatIndex.FILE));
    }

    /** The fragment store of {@code out}: the one in memory while {@code fragments.jsonl} is unchanged. */
    static synchronized FragmentStore store(Path out) {
        own(out);
        Stamp now = Stamp.of(out.resolve(FragmentStore.FILE));
        if (store == null || now == null || !now.equals(storeStamp)) {
            store = FragmentStore.open(out);
            storeStamp = now;
        }
        return store;
    }

    /** {@code fragments} was just saved to {@code out}. */
    static synchronized void savedStore(Path out, FragmentStore fragments) {
        own(out);
        store = fragments;
        storeStamp = Stamp.of(out.resolve(FragmentStore.FILE));
    }

    /** The graph in memory while {@code graph.bin} is the one it came from, else {@code null}. */
    static synchronized @Nullable Graph graph(Path out) {
        own(out);
        Stamp now = Stamp.of(out.resolve(GraphOutputs.SNAPSHOT));
        return graph != null && now != null && now.equals(graphStamp) ? graph : null;
    }

    /** {@code g} was just read from, or written to, {@code out}'s {@code graph.bin}. */
    static synchronized void sawGraph(Path out, CodeGraph g, GraphSnapshot.Key key) {
        own(out);
        graph = new Graph(g, key);
        graphStamp = Stamp.of(out.resolve(GraphOutputs.SNAPSHOT));
    }

    /** Test seam: forget everything. */
    static synchronized void clear() {
        dir = null;
        stats = null;
        statsStamp = null;
        store = null;
        storeStamp = null;
        graph = null;
        graphStamp = null;
    }

    private static void own(Path out) {
        Path abs = out.toAbsolutePath().normalize();
        if (Objects.equals(dir, abs)) return;
        clear();
        dir = abs;
    }
}
