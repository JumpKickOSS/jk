// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The heap a native-image builder gets. A module's first build, and any build whose dependencies
 * changed or whose classpath grew by more than {@value #GROWTH_PERCENT}%, gets the {@link
 * #generous} heap. Otherwise it gets twice the largest peak learned from its
 * earlier builds, at least {@value #FLOOR_MIB} MiB, scaled by how much the classpath grew and never
 * more than the generous heap. A build that ends for memory gets one {@link #retry}.
 */
public final class NativeHeap {

    /**
     * Share of the worker cap the generous heap is: what the GraalVM driver itself asks for in a
     * container.
     */
    static final double GENEROUS_SHARE = 0.85;

    /** Smallest learned builder heap, in MiB. */
    static final long FLOOR_MIB = 2_048;

    /** Learned heap over the largest learned peak, in percent. */
    static final int HEADROOM_PERCENT = 200;

    /** Classpath growth, in percent, past which the learned peaks no longer apply. */
    static final int GROWTH_PERCENT = 25;

    private NativeHeap() {}

    /**
     * What an image is built from: the classpath's bytes, and a digest of what is on it — each
     * entry's file name, so a dependency added, removed or moved to another version changes it —
     * and the reachability metadata directories.
     */
    public record Inputs(long bytes, String shape) {

        /** The inputs of a build of {@code classpath} with {@code metadataDirs}. */
        public static Inputs of(List<Path> classpath, List<Path> metadataDirs) {
            long bytes = 0;
            List<String> names = new ArrayList<>();
            for (Path entry : classpath) {
                names.add("cp:" + entry.getFileName());
                try {
                    if (Files.isRegularFile(entry)) bytes += Files.size(entry);
                } catch (IOException e) {
                    // an unreadable size counts as nothing; the digest still names the entry
                }
            }
            for (Path dir : metadataDirs) names.add("meta:" + dir.getFileName());
            names.sort(null);
            return new Inputs(bytes, digest(String.join("\n", names)));
        }

        /** {@code <bytes>:<shape>}, the form {@link LearnedHeaps#inputs} stores. */
        public String encode() {
            return bytes + ":" + shape;
        }

        /** The inputs {@link #encode} wrote, or {@code null} for none or a torn value. */
        static @Nullable Inputs decode(String text) {
            int colon = text == null ? -1 : text.indexOf(':');
            if (colon <= 0) return null;
            try {
                return new Inputs(Long.parseLong(text.substring(0, colon)), text.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The builder heap, whether it was learned, and why, for the build's output. */
    public record Choice(long xmxBytes, boolean learned, String why) {}

    /**
     * The heap {@code key}'s build of {@code now} starts with, {@code generousBytes} being the most
     * a builder is given.
     */
    public static Choice choose(LearnedHeaps heaps, HeapScope.Key key, Inputs now, long generousBytes) {
        long peak = heaps.peak(key);
        Inputs then = Inputs.decode(heaps.inputs(key));
        if (peak <= 0 || then == null) return generous(generousBytes, "no earlier build of this module to learn from");
        if (!then.shape().equals(now.shape())) {
            return generous(generousBytes, "its dependencies changed since the build it was learned from");
        }
        long grownPercent = then.bytes() <= 0 ? 0 : (now.bytes() - then.bytes()) * 100 / then.bytes();
        if (grownPercent > GROWTH_PERCENT) {
            return generous(
                    generousBytes, "its classpath grew " + grownPercent + "% since the build it was learned from");
        }
        long sized = Math.max(FLOOR_MIB << 20, peak / 100 * HEADROOM_PERCENT);
        if (grownPercent > 0) sized = sized / 100 * (100 + grownPercent);
        sized = Math.max(sized, heaps.good(key));
        long rounded = (sized + LearnedHeaps.STEP_BYTES - 1) / LearnedHeaps.STEP_BYTES * LearnedHeaps.STEP_BYTES;
        if (rounded >= generousBytes) return generous(generousBytes, "its learned heap reaches the most it can have");
        return new Choice(
                rounded, true, "learned: twice the " + WorkerLeases.format(peak) + " its earlier builds peaked at");
    }

    /**
     * A rerun of a native-image build that ended for memory: its heap, its {@code --parallelism}
     * ({@code 0} leaves the driver's own), and the line that says why.
     */
    public record Retry(long xmxBytes, int parallelism, String note) {}

    /**
     * The one rerun a native-image build that ended with {@code cause} gets, or {@code null}. A
     * learned heap that ran out, or was killed for memory, reruns at {@code generousBytes}. A builder
     * killed at its own worker cap used memory outside its heap: it reruns with a quarter less heap
     * and half the {@code cores} as threads, which hold that memory, unless the args set the threads.
     * A user-pinned heap ({@code heap} null) is not rerun.
     */
    public static @Nullable Retry retry(
            @Nullable Choice heap, WorkerFate.Cause cause, long generousBytes, int cores, boolean threadsPinned) {
        if (heap == null) return null;
        boolean killed = cause == WorkerFate.Cause.KILLED_FOR_MEMORY;
        if (heap.learned() && (cause == WorkerFate.Cause.HEAP_EXHAUSTED || killed)) {
            return new Retry(generousBytes, 0, HeapNotes.line(generousBytes, heap.xmxBytes(), killed));
        }
        if (cause != WorkerFate.Cause.OVER_WORKER_CAP) return null;
        long smaller = Math.max(WorkerLeases.MIN_XMX, heap.xmxBytes() / 4 * 3);
        int threads = threadsPinned ? 0 : Math.max(1, cores / 2);
        return new Retry(
                smaller,
                threads,
                "retried with " + WorkerLeases.format(smaller) + " heap"
                        + (threads > 0 ? " and --parallelism=" + threads : "")
                        + " after native-image was killed at its worker cap");
    }

    /** The generous builder heap: {@link #GENEROUS_SHARE} of the worker cap under {@code capacityBytes}. */
    public static long generous(long capacityBytes) {
        long workerCap = WorkerRss.workerCapBytes(0, capacityBytes);
        return Math.max(WorkerLeases.MIN_XMX, (long) ((workerCap > 0 ? workerCap : capacityBytes) * GENEROUS_SHARE));
    }

    private static Choice generous(long generousBytes, String why) {
        return new Choice(generousBytes, false, why);
    }

    private static String digest(String text) {
        return Hashing.sha256Hex(text).substring(0, 24);
    }
}
