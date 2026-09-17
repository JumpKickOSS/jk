// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JvmOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compiler worker's heap follows the module: a wide classpath asks for more than the memory
 * plan's share, a small module keeps the share, nothing exceeds what the host can give one worker,
 * and a user who pinned worker memory keeps their number.
 */
class WorkerHeapTest {

    private static final long MIB = WorkerHeap.MIB;

    @AfterEach
    void reset() {
        SessionContext.reset();
        JvmOptions.resetSharedPlanForTests();
    }

    @Test
    void demand_grows_with_classpath_entries_jar_bytes_and_source_bytes() {
        long small = WorkerHeap.demandBytes(10, 20 * MIB, 500 * 1024);
        // A Quarkus application's test compile: 379 jars of 170 MiB and 22 MiB of test sources.
        long quarkusTests = WorkerHeap.demandBytes(379, 170 * MIB, 22 * MIB);
        assertThat(small)
                .isEqualTo(
                        WorkerHeap.BASE_BYTES + 10 * MIB + 20 * MIB / 8 + 500 * 1024 * WorkerHeap.HEAP_PER_SOURCE_BYTE);
        assertThat(quarkusTests)
                .as("a worker that thrashed at 1457 MiB needs to be sized well past it")
                .isGreaterThan(2048 * MIB);
    }

    @Test
    void a_small_module_keeps_the_plans_share_and_a_wide_one_rounds_up_to_a_step() {
        long share = 700 * MIB;
        long ceiling = 8192 * MIB;
        assertThat(WorkerHeap.sizeBytes(WorkerHeap.demandBytes(10, 20 * MIB, 500 * 1024), share, ceiling))
                .isEqualTo(share);
        long wide = WorkerHeap.sizeBytes(WorkerHeap.demandBytes(379, 170 * MIB, 22 * MIB), share, ceiling);
        assertThat(wide).isGreaterThan(share);
        assertThat(wide % WorkerHeap.STEP)
                .as("sizes round to a step so modules of one size share a worker")
                .isZero();
    }

    @Test
    void the_ceiling_caps_a_demand_and_a_floor_above_it_still_wins() {
        long ceiling = 1024 * MIB;
        assertThat(WorkerHeap.sizeBytes(WorkerHeap.demandBytes(2000, 4096 * MIB, 200 * MIB), 512 * MIB, ceiling))
                .isEqualTo(ceiling);
        assertThat(WorkerHeap.sizeBytes(256 * MIB, 1500 * MIB, ceiling)).isEqualTo(1500 * MIB);
    }

    @Test
    void the_retry_heap_is_double_until_the_ceiling_leaves_no_room() {
        long ceiling = WorkerHeap.ceilingBytes();
        assertThat(ceiling).isGreaterThan(32L << 20);
        Long grown = WorkerHeap.grown(ceiling / 4);
        assertThat(grown).isNotNull().isEqualTo(Math.min(ceiling, ceiling / 4 * 2));
        assertThat(WorkerHeap.grown(ceiling))
                .as("at the ceiling there is nothing left to try")
                .isNull();
    }

    @Test
    void a_user_who_pinned_worker_memory_keeps_it(@TempDir Path dir) {
        SessionContext.install(SessionContext.current().withJvm(new PluginTuning(null, null, null, List.of("-Xmx2g"))));
        assertThat(WorkerHeap.forRequest(request(dir, List.of()))).isNull();
    }

    @Test
    void the_request_is_sized_from_its_classpath_on_disk(@TempDir Path dir) throws IOException {
        SessionContext.install(SessionContext.current().withJvm(PluginTuning.NONE));
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Path jar = dir.resolve("lib" + i + ".jar");
            Files.write(jar, new byte[64 * 1024]);
            jars.add(jar);
        }
        Files.writeString(dir.resolve("C.java"), "class C {}");
        long expected = WorkerHeap.demandBytes(4, 4 * 64 * 1024, Files.size(dir.resolve("C.java")));
        assertThat(WorkerHeap.demandBytes(request(dir, jars))).isEqualTo(expected);
        Long heap = WorkerHeap.forRequest(request(dir, jars));
        assertThat(heap).isNotNull();
        assertThat(heap).isEqualTo(WorkerHeap.sizeBytes(expected, 0L, WorkerHeap.ceilingBytes()));
    }

    @Test
    void the_heap_is_the_last_jvm_flag_so_it_wins_over_the_plans_share() {
        SessionContext.install(SessionContext.current().withJvm(PluginTuning.NONE));
        List<String> flags = ForkedJavac.workerJvmFlags(List.of("-XX:AOTCache=x.aot"), 1536 * MIB);
        assertThat(flags).first().isEqualTo("-XX:AOTCache=x.aot");
        assertThat(flags).last().isEqualTo("-Xmx1536m");
        assertThat(ForkedJavac.workerJvmFlags(List.of(), null)).noneMatch(f -> f.equals("-Xmx1536m"));
    }

    @Test
    void the_failure_names_the_module_and_both_heaps() {
        IOException twice =
                WorkerHeap.exhausted("g:app compile-test", 1024 * MIB, 2048 * MIB, "Terminating due to OOM");
        assertThat(twice.getMessage())
                .contains("g:app compile-test")
                .contains("1024 MiB")
                .contains("2048 MiB")
                .contains("Terminating due to OOM");
        IOException capped = WorkerHeap.exhausted("g:app compile-test", 4096 * MIB, null, "");
        assertThat(capped.getMessage()).contains("4096 MiB").contains("the most this host can give");
    }

    private static ForkedJavac.Request request(Path dir, List<Path> classpath) {
        return new ForkedJavac.Request(
                null,
                dir.resolve("worker.jar"),
                List.of(dir.resolve("C.java")),
                classpath,
                List.of(),
                dir.resolve("classes"),
                dir.resolve("gen"),
                25,
                List.of());
    }
}
