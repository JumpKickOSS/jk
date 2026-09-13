// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the engine tells the Kotlin worker when it wants classpath snapshots and nothing compiled:
 * the {@code snapshot} op, the compile's own snapshot directory (so the compile that may follow
 * finds the files), and the entries as compile-classpath lines.
 */
class KotlincSnapshotsTest {

    @Test
    void the_spec_names_the_op_the_snapshot_dir_and_the_entries(@TempDir Path dir) throws IOException {
        Path snapshots = dir.resolve("kotlin-cp-snapshots");
        Path lib = source(dir, "lib.jar", "lib");
        Path dep = source(dir, "dep.jar", "dep");
        KotlincRequest request = KotlincRequest.builder()
                .sources(List.of(source(dir, "Main.kt", "fun main() {}")))
                .classpath(List.of(lib, dep, source(dir, "stdlib.jar", "stdlib")))
                .outputDir(dir.resolve("classes"))
                .jvmTarget(21)
                .workerClasspath(List.of(source(dir, "worker.jar", "worker")))
                .javaHome(Files.createDirectories(dir.resolve("jdk")))
                .snapshotDir(snapshots)
                .build();

        Path spec = KotlincSnapshots.writeSpec(request, List.of(lib, dep));
        try {
            PluginSpec read = PluginSpec.read(spec);
            assertThat(read.op()).isEqualTo(PluginProtocol.OP_SNAPSHOT);
            assertThat(read.snapshotDir()).isEqualTo(snapshots.toAbsolutePath());
            assertThat(read.compileClasspath())
                    .as("only the entries asked for, not the whole compile classpath")
                    .containsExactly(lib.toAbsolutePath(), dep.toAbsolutePath());
            assertThat(read.sources()).isEmpty();
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    @Test
    void a_request_without_a_snapshot_dir_snapshots_nothing(@TempDir Path dir) throws IOException {
        KotlincRequest request = KotlincRequest.builder()
                .sources(List.of(source(dir, "Main.kt", "fun main() {}")))
                .outputDir(dir.resolve("classes"))
                .jvmTarget(21)
                .workerClasspath(List.of(source(dir, "worker.jar", "worker")))
                .javaHome(Files.createDirectories(dir.resolve("jdk")))
                .build();

        assertThat(KotlincSnapshots.snapshot(request, List.of(source(dir, "lib.jar", "lib")), WorkerEnv.strict()))
                .as("no worker is forked: the compile runs no snapshot-based IC either")
                .isEmpty();
    }

    private static Path source(Path dir, String name, String body) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, body);
        return f;
    }
}
