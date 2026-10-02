// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeHeapTest {

    private static final long GIB = 1L << 30;
    private static final long GENEROUS = 8 * GIB;

    /**
     * A module builds first at the generous heap; once a build is learned it gets twice its peak,
     * growing with the classpath. A dependency change, or a classpath that grew past the bound,
     * goes back to generous until the next build is learned.
     */
    @Test
    void a_learned_heap_holds_while_the_inputs_hold(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("heaps"));
        HeapScope.Key key = new HeapScope.Key(dir, "g:app", HeapScope.NATIVE_IMAGE, 25);
        Path app = jar(dir, "app-1.0.jar", 1_000_000);
        Path lib = jar(dir, "lib-2.3.jar", 9_000_000);
        NativeHeap.Inputs built = NativeHeap.Inputs.of(List.of(app, lib), List.of());

        NativeHeap.Choice first = NativeHeap.choose(heaps, key, built, GENEROUS);
        assertThat(first.learned()).isFalse();
        assertThat(first.xmxBytes()).isEqualTo(GENEROUS);

        heaps.note(key, 3 * GIB);
        heaps.inputs(key, built.encode());
        NativeHeap.Choice learned = NativeHeap.choose(heaps, key, built, GENEROUS);
        assertThat(learned.learned()).isTrue();
        assertThat(learned.xmxBytes()).isEqualTo(6 * GIB);

        jar(dir, "app-1.0.jar", 2_000_000);
        NativeHeap.Choice grown =
                NativeHeap.choose(heaps, key, NativeHeap.Inputs.of(List.of(app, lib), List.of()), GENEROUS);
        assertThat(grown.learned()).as("10% more classpath").isTrue();
        assertThat(grown.xmxBytes()).isGreaterThan(6 * GIB).isLessThan(7 * GIB);

        jar(dir, "app-1.0.jar", 5_000_000);
        NativeHeap.Choice far =
                NativeHeap.choose(heaps, key, NativeHeap.Inputs.of(List.of(app, lib), List.of()), GENEROUS);
        assertThat(far.learned()).as("40% more classpath").isFalse();
        assertThat(far.why()).contains("grew 40%");

        jar(dir, "app-1.0.jar", 1_000_000);
        Path newer = jar(dir, "lib-2.4.jar", 9_000_000);
        NativeHeap.Choice bumped =
                NativeHeap.choose(heaps, key, NativeHeap.Inputs.of(List.of(app, newer), List.of()), GENEROUS);
        assertThat(bumped.learned())
                .as("one dependency moved to another version")
                .isFalse();
        assertThat(bumped.why()).contains("dependencies changed");

        NativeHeap.Choice metadata = NativeHeap.choose(
                heaps, key, NativeHeap.Inputs.of(List.of(app, lib), List.of(dir.resolve("reachability"))), GENEROUS);
        assertThat(metadata.learned()).as("reachability metadata added").isFalse();
    }

    @Test
    void a_small_peak_is_raised_to_the_floor_and_a_large_one_stays_generous(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("heaps"));
        HeapScope.Key key = new HeapScope.Key(dir, "g:app", HeapScope.NATIVE_IMAGE, 25);
        NativeHeap.Inputs inputs = NativeHeap.Inputs.of(List.of(jar(dir, "app.jar", 1_000)), List.of());
        heaps.inputs(key, inputs.encode());

        heaps.note(key, 300L << 20);
        assertThat(NativeHeap.choose(heaps, key, inputs, GENEROUS).xmxBytes()).isEqualTo(NativeHeap.FLOOR_MIB << 20);

        heaps.note(key, 5 * GIB);
        NativeHeap.Choice big = NativeHeap.choose(heaps, key, inputs, GENEROUS);
        assertThat(big.learned()).isFalse();
        assertThat(big.xmxBytes()).isEqualTo(GENEROUS);
    }

    @Test
    void inputs_round_trip_through_the_store(@TempDir Path dir) throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir.resolve("heaps"));
        HeapScope.Key key = new HeapScope.Key(dir, "g:app", HeapScope.NATIVE_IMAGE, 25);
        NativeHeap.Inputs inputs = NativeHeap.Inputs.of(List.of(jar(dir, "app.jar", 42)), List.of());
        heaps.note(key, GIB);
        heaps.inputs(key, inputs.encode());
        heaps.note(key, 2 * GIB);

        assertThat(NativeHeap.Inputs.decode(heaps.inputs(key))).isEqualTo(inputs);
        assertThat(heaps.peak(key)).isEqualTo(2 * GIB);
        assertThat(NativeHeap.Inputs.decode("")).isNull();
        assertThat(NativeHeap.Inputs.decode("x:y")).isNull();
    }

    /** native-image takes builder JVM flags with -J; the GC log the peak is read from is the builder's. */
    @Test
    void a_native_image_builders_gc_log_is_a_builder_flag() throws Exception {
        List<String> command = List.of("/opt/graal/bin/native-image", "-J-Xmx4096m", "-cp", "app.jar", "app.Main");
        JvmOptions.notePlannedHeap("-J-Xmx4096m");
        HeapScope.Key key = new HeapScope.Key(Path.of("/ws/app"), "g:app", HeapScope.NATIVE_IMAGE, 25);
        WorkerGc.Watch watch = HeapScope.call(
                key, () -> WorkerGc.watch(command, JvmOptions.HeapChoice.inspect(command), LearnedHeaps.engine()));
        try {
            assertThat(watch.command().get(1)).startsWith("-J" + WorkerGc.FLAG_PREFIX);
            assertThat(WorkerGc.logged(watch.command())).isTrue();
        } finally {
            watch.finish();
            JvmOptions.forgetPlannedHeapForTests("-J-Xmx4096m");
        }
    }

    private static Path jar(Path dir, String name, int bytes) throws Exception {
        return Files.write(dir.resolve(name), new byte[bytes]);
    }
}
