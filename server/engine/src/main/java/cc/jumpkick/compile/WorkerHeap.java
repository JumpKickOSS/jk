// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The heap a compiler worker JVM starts with, sized for the module it compiles. A Zinc worker holds
 * an index of every classpath entry and javac's model of every source, so a module with a
 * several-hundred-jar test classpath and a few thousand sources needs more than the memory plan's
 * per-worker share, which is cut for a full set of parallel workers. The demand is estimated from
 * the request: a base, one unit per classpath entry, a fraction of the jar bytes and a multiple of
 * the source bytes. The heap is the larger of the plan's share and the demand rounded up to a
 * {@link #STEP}, never above what the host can give a single worker, so modules of about one size
 * share a worker.
 *
 * <p>Nothing here applies when the user pinned worker memory ({@code --ram-percent}, {@code [jvm]
 * args} with a heap flag, {@code [test] jvm-args}, or a module {@code -J} flag): their number is
 * the worker's heap, the worker budget does not rewrite it, and a worker that runs out of it is
 * reported as such.
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

    /** Heap sizes round up to a multiple of this so modules of about one size share a worker. */
    static final long STEP = 256 * MIB;

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
     * The heap for a worker that will compile {@code req}: {@code demand} rounded up to a {@link
     * #STEP}, at least {@code floor}, at most {@code ceiling}. A floor above the ceiling wins, so a
     * worker never starts with less than the plan already gave it.
     */
    static long sizeBytes(long demand, long floor, long ceiling) {
        long rounded = Math.max(STEP, (demand + STEP - 1) / STEP * STEP);
        return Math.max(floor, Math.min(rounded, ceiling));
    }

    /**
     * The heap a worker for {@code req} starts with, or {@code null} when the user pinned worker
     * memory. The floor is the memory plan's per-worker share (nothing, when no plan has been
     * applied); the ceiling is what this host could give a lone worker.
     */
    static @Nullable Long forRequest(ForkedJavac.Request req) {
        if (!JvmOptions.autoHeapEnabled()) return null;
        HeapPlan.Plan plan = JvmOptions.processHeapPlan();
        long floor = plan != null ? plan.xmxBytes() : 0L;
        long estimated = sizeBytes(demandBytes(req), floor, ceilingBytes());
        return LearnedHeaps.engine()
                .choose(
                        SessionContext.current().workingDir(),
                        moduleOf(req.label()),
                        kindOf(req.label()),
                        JvmOptions.hostFeature(ForkedJavac.workerJavaHome(req)),
                        estimated);
    }

    /** {@code group:name} from a compile label, or empty when the label does not carry one. */
    static String moduleOf(String label) {
        if (label == null || label.isBlank()) return "";
        int sp = label.lastIndexOf(' ');
        String head = (sp < 0 ? label : label.substring(0, sp)).trim();
        return head.indexOf(':') > 0 ? head : "";
    }

    /** {@link HeapScope#JAVA_TEST_COMPILE} when the label names a test compile, else java-compile. */
    static String kindOf(String label) {
        if (label != null && label.contains(TaskNames.COMPILE_TEST)) return HeapScope.JAVA_TEST_COMPILE;
        return HeapScope.JAVA_COMPILE;
    }

    /** The most a single worker on this host can have: a lone worker's share of the memory plan. */
    static long ceilingBytes() {
        return JvmOptions.fitWorkerBudget(HeapPlan.compute(MemoryProbe.probe().availableBytes(), 1))
                .xmxBytes();
    }

    /** True when {@code output} names an own-heap exhaustion, including the JVM's terminating banner. */
    static boolean outOfMemory(String output) {
        return WorkerFate.mentionsHeap(output) || (output != null && output.contains("java.lang.OutOfMemoryError"));
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
                .append("; raise it with [jvm] args = [\"-Xmx...\"] or [test] jvm-args = [\"-Xmx...\"]");
        if (!output.isBlank()) msg.append("\n--- zinc worker output ---\n").append(output);
        return new IOException(msg.toString());
    }

    /** The learned-heap key of a compile of {@code req}, or {@code null} when its label names no module. */
    static HeapScope.@Nullable Key key(ForkedJavac.Request req) {
        String module = moduleOf(req.label());
        if (module.isBlank()) return null;
        return new HeapScope.Key(
                SessionContext.current().workingDir(),
                module,
                kindOf(req.label()),
                JvmOptions.hostFeature(ForkedJavac.workerJavaHome(req)));
    }

    /**
     * The failure a compile reports when the user pinned {@code pin} and that heap ran out. The pin
     * is not changed and the compile is not retried.
     */
    static IOException pinned(String label, String pin, String output) {
        String what = label.isBlank() ? "the compiler worker" : "the compiler worker for " + label;
        String named = pin == null || pin.isBlank() ? "heap" : pin;
        String msg = what + " ran out of the pinned heap " + named
                + "; that setting is the worker's heap — raise it with [jvm] args = [\"-Xmx...\"]"
                + " or [test] jvm-args = [\"-Xmx...\"]";
        if (output != null && !output.isBlank()) msg = msg + "\n--- zinc worker output ---\n" + output;
        return new IOException(msg);
    }

    static long mib(long bytes) {
        return bytes / MIB;
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
