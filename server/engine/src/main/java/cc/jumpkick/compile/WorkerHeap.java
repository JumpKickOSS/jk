// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.HeapLadder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The heap one compile is estimated to need, and how a compiler worker that ran out of heap is
 * reported. A Zinc compile holds an index of every classpath entry and javac's model of every
 * source, so the demand is estimated from the request: a base, one unit per classpath entry, a
 * fraction of the jar bytes and a multiple of the source bytes. The resident Java compiler worker
 * is not sized from it — its heap grows and shrinks with what its compiles hold — but the estimate
 * is what deciding how many compiles one worker runs at once can weigh.
 */
final class WorkerHeap {

    private WorkerHeap() {}

    static final long MIB = 1L << 20;

    /** What a Zinc worker holds before the first classpath entry: the compiler, Zinc and the bridge. */
    static final long BASE_BYTES = 384 * MIB;

    /** Per classpath entry: Zinc's entry stamp and class listing, javac's zip index of it. */
    static final long PER_CLASSPATH_ENTRY_BYTES = MIB;

    /** One byte of heap per this many jar bytes: javac's and Zinc's indexes of the entries' contents. */
    static final int JAR_BYTES_PER_HEAP_BYTE = 8;

    /**
     * Heap per byte of source: javac's trees, symbols and attributed types for a whole-module compile,
     * and Zinc's per-source relations. A 22 MB test tree compiled in one pass holds about two
     * gigabytes.
     */
    static final long HEAP_PER_SOURCE_BYTE = 96;

    /** The estimated heap demand of a compile with these inputs, in bytes. */
    static long demandBytes(int classpathEntries, long classpathBytes, long sourceBytes) {
        return BASE_BYTES
                + PER_CLASSPATH_ENTRY_BYTES * Math.max(0, classpathEntries)
                + Math.max(0, classpathBytes) / JAR_BYTES_PER_HEAP_BYTE
                + HEAP_PER_SOURCE_BYTE * Math.max(0, sourceBytes);
    }

    /** {@link #demandBytes(int, long, long)} for {@code req}: its compile and processor paths, its sources. */
    static long demandBytes(ForkedJavac.Request req) {
        int entries = req.classpath().size() + req.processorPath().size();
        long bytes = fileBytes(req.classpath()) + fileBytes(req.processorPath());
        return demandBytes(entries, bytes, fileBytes(req.sources()));
    }

    /**
     * The failure a compile of {@code label} reports after its worker ran out of every heap in
     * {@code ranOut}, oldest first.
     */
    static IOException exhausted(String label, List<Long> ranOut, String output) {
        String what = label.isBlank() ? "the compiler worker" : "the compiler worker for " + label;
        StringBuilder msg = new StringBuilder(what)
                .append(' ')
                .append(HeapLadder.ranOut(ranOut))
                .append("; raise -Xmx in [jvm] args or [test] jvm-args");
        if (!output.isBlank()) msg.append("\n--- zinc worker output ---\n").append(output);
        return new IOException(msg.toString());
    }

    private static long fileBytes(List<Path> entries) {
        long total = 0;
        for (Path p : entries) {
            try {
                if (Files.isRegularFile(p)) total += Files.size(p);
            } catch (IOException e) {
                // an unreadable entry costs nothing here; the compile itself will name it
            }
        }
        return total;
    }
}
