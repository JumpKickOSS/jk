// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.task.IoLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputTreesTest {

    @AfterEach
    void clear() {
        InputTrees.resetForTest();
        RequestScope.clearAll();
        SessionContext.reset();
        PathUtil.resetWalks();
    }

    @Test
    void nested_root_is_a_prefix_of_one_walk(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("src/main/resources/x.txt"), "x");

        inRequest(() -> {
            PathUtil.resetWalks();
            InputTrees.coverModule(dir);
            assertThat(PathUtil.walks()).isEqualTo(1);
            assertThat(InputTrees.of(src).withExtension(".java")).hasSize(1);
            assertThat(InputTrees.of(dir.resolve("src")).anyExtension(".txt")).isTrue();
            assertThat(PathUtil.walks()).isEqualTo(1);
        });
    }

    @Test
    void vfs_off_is_stream_only(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 0));
        inRequest(() -> {
            InputTrees.coverModule(dir);
            assertThat(InputTrees.of(src).overflow()).isTrue();
            assertThat(InputTrees.of(src).withExtension(".java")).hasSize(1);
        });
    }

    @Test
    void second_job_is_stream_only_when_pool_is_full(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));
        InputTrees.fillPoolForTest();
        long full = InputTrees.poolUsedBytes();
        assertThat(full).isEqualTo(InputTrees.poolMaxBytes());
        inRequest(() -> {
            InputTrees.coverModule(dir);
            assertThat(InputTrees.of(src).overflow()).isTrue();
            assertThat(InputTrees.of(src).withExtension(".java")).hasSize(1);
            assertThat(InputTrees.poolUsedBytes()).isEqualTo(full);
        });
    }

    @Test
    void finish_job_returns_bytes_to_the_pool(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));
        inRequest(() -> {
            InputTrees.coverModule(dir);
            assertThat(InputTrees.poolUsedBytes()).isPositive();
        });
        assertThat(InputTrees.poolUsedBytes()).isZero();
    }

    @Test
    void vfs_max_mb_is_clamped_by_the_process_pool() {
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 1024));
        assertThat(InputTrees.growLimitBytes()).isEqualTo(192L * 1024 * 1024);
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 0));
        assertThat(InputTrees.growLimitBytes()).isZero();
    }

    @Test
    void extra_src_is_a_separate_covering_key(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Path extra = Files.createDirectories(dir.resolve("overlay"));
        Files.writeString(extra.resolve("B.java"), "class B {}");
        inRequest(() -> {
            InputTrees.coverModule(dir);
            var srcSnap = InputTrees.of(dir.resolve("src"));
            var extraSnap = InputTrees.of(extra);
            assertThat(srcSnap.withExtension(".java"))
                    .containsExactly(src.resolve("A.java").toAbsolutePath().normalize());
            assertThat(extraSnap.withExtension(".java"))
                    .containsExactly(extra.resolve("B.java").toAbsolutePath().normalize());
            long after = PathUtil.walks();
            assertThat(InputTrees.of(extra).withExtension(".java")).hasSize(1);
            assertThat(PathUtil.walks()).isEqualTo(after);
        });
    }

    @Test
    void overflow_keeps_already_recorded_listings(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Path other = Files.createDirectories(dir.resolve("other"));
        Files.writeString(other.resolve("B.java"), "class B {}");
        inRequest(() -> {
            InputTrees.coverModule(dir);
            assertThat(InputTrees.of(src).overflow()).isFalse();
            InputTrees.fillPoolForTest();
            assertThat(InputTrees.of(other).overflow()).isTrue();
            assertThat(InputTrees.of(src).overflow()).isFalse();
            assertThat(InputTrees.of(src).withExtension(".java")).hasSize(1);
        });
    }

    @Test
    void file_ref_carries_size_mtime_nanos_and_regular_bit(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        inRequest(() -> {
            InputTrees.coverModule(dir);
            InputTrees.FileRef ref = InputTrees.of(src).files().getFirst();
            assertThat(ref.regular()).isTrue();
            assertThat(ref.size()).isPositive();
            assertThat(ref.mtimeMillis()).isPositive();
            assertThat(ref.mtimeNanos()).isPositive();
            assertThat(ref.name()).isEqualTo("A.java");
        });
    }

    @Test
    void last_job_status_json_is_spliced_into_status_ack(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        assertThat(InputTrees.lastStatusJson()).isEmpty();
        inRequest(() -> InputTrees.coverModule(dir));
        String vfs = InputTrees.lastStatusJson();
        assertThat(vfs).contains("\"maxMb\":");
        assertThat(vfs).contains("\"poolMaxMb\":");
        assertThat(vfs).contains("\"poolUsedMb\":");
        assertThat(vfs).contains("\"nodes\":");
        assertThat(vfs).contains("\"bytes\":");
        assertThat(vfs).contains("\"walks\":");
        assertThat(vfs).contains("\"hits\":");
        assertThat(vfs).contains("\"streamOnly\":false");
        String ack = InputTrees.appendToStatusAck("{\"type\":\"status-ack\"}");
        assertThat(ack).startsWith("{\"type\":\"status-ack\",\"vfs\":{");
        assertThat(ack).endsWith("}");
    }

    private static void inRequest(Runnable body) {
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            SessionContext.runWhere(Session.defaults().withIo(ledger), body);
        } finally {
            InputTrees.finishJob();
            IoLedger.close();
        }
    }
}
