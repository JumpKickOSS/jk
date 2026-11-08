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
 * A compile's estimated heap demand follows its inputs, and a worker that ran out of heap names the
 * module and every heap it tried.
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
    void the_demand_is_read_off_the_classpath_on_disk(@TempDir Path dir) throws IOException {
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
    }

    @Test
    void the_failure_names_the_module_and_every_heap() {
        IOException spent = WorkerHeap.exhausted(
                "g:app compile-test", List.of(256 * MIB, 512 * MIB, 1024 * MIB, 2048 * MIB), "Terminating due to OOM");
        assertThat(spent.getMessage())
                .contains("g:app compile-test")
                .contains("ran out of heap at 256 MiB, 512 MiB, 1.0 GiB and 2.0 GiB;")
                .contains("Terminating due to OOM");
        IOException capped = WorkerHeap.exhausted("g:app compile-test", List.of(4096 * MIB), "");
        assertThat(capped.getMessage()).contains("4.0 GiB").contains("the most this host can give");
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
