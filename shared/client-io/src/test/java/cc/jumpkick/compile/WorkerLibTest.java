// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLibTest {

    @Test
    void id_from_m2_layout_and_filename() {
        Path m2 = Path.of("/home/u/.jk/store/repos/local/cc/jumpkick/jk-kotlin-compiler/0.10.1/jk-kotlin-compiler-0.10.1.jar");
        assertThat(WorkerLib.idFromWorkerJar(m2)).isEqualTo("jk-kotlin-compiler");
        assertThat(WorkerLib.idFromWorkerJar(Path.of("/tmp/jk-test-runner-0.10.1.jar")))
                .isEqualTo("jk-test-runner");
        assertThat(WorkerLib.stripJarVersion("plugin-sdk-0.1.0.jar")).isEqualTo("plugin-sdk");
        assertThat(WorkerLib.looksLikeVersion("0.10.1")).isTrue();
        assertThat(WorkerLib.looksLikeVersion("libs")).isFalse();
    }

    @Test
    void materialize_hardlinks_and_paths_if_present(@TempDir Path store) throws Exception {
        // Redirect store via JK_STORE_DIR for this test process.
        String prev = System.getenv("JK_STORE_DIR");
        // JkDirs reads env at call time — use synthetic files under a fake store by calling
        // materialize with id and checking via dir under real store would pollute ~/.jk.
        // Instead exercise link layout under a temp dir by testing pure functions + materialize
        // through path construction: temporarily set JK_HOME to temp.
        // WorkerLib.root() uses JkDirs.store() → JK_STORE_DIR or JK_HOME/store.
        // We can't set env easily; materialize writes to real store — use unique id and remove.
        Path worker = store.resolve("worker-src/jk-demo-1.0.0.jar");
        Path dep = store.resolve("dep-src/dep-a.jar");
        Files.createDirectories(worker.getParent());
        Files.createDirectories(dep.getParent());
        Files.writeString(worker, "worker-bytes");
        Files.writeString(dep, "dep-bytes");

        String id = "jk-demo-test-" + System.nanoTime();
        try {
            Path lib = WorkerLib.materialize(id, worker, List.of(dep));
            assertThat(lib).isEqualTo(WorkerLib.dir(id));
            assertThat(Files.isRegularFile(lib.resolve("jk-demo-1.0.0.jar"))).isTrue();
            assertThat(Files.isRegularFile(lib.resolve("dep-a.jar"))).isTrue();
            assertThat(Files.isRegularFile(lib.resolve(WorkerLib.ORDER_FILE))).isTrue();

            List<Path> paths = WorkerLib.pathsIfPresent(id);
            assertThat(paths).isNotNull();
            assertThat(paths).hasSize(2);
            assertThat(paths.get(0).getFileName().toString()).isEqualTo("jk-demo-1.0.0.jar");
            assertThat(paths.get(1).getFileName().toString()).isEqualTo("dep-a.jar");
            assertThat(Files.readString(paths.get(0))).isEqualTo("worker-bytes");
            assertThat(Files.readString(paths.get(1))).isEqualTo("dep-bytes");

            // Deleting the source must not remove lib content (hardlink or copy both keep bytes).
            Files.delete(worker);
            Files.delete(dep);
            assertThat(Files.readString(paths.get(0))).isEqualTo("worker-bytes");

            WorkerLib.remove(id);
            assertThat(WorkerLib.pathsIfPresent(id)).isNull();
        } finally {
            try {
                WorkerLib.remove(id);
            } catch (Exception ignored) {
                /* cleanup */
            }
        }
    }

    @Test
    void worker_classpath_prefers_lib_when_materialized(@TempDir Path store) throws Exception {
        Path worker = store.resolve("w/jk-pref-1.0.jar");
        Path dep = store.resolve("d/extra.jar");
        Files.createDirectories(worker.getParent());
        Files.createDirectories(dep.getParent());
        Files.writeString(worker, "w");
        Files.writeString(dep, "d");
        // Sidecar points at a third path that should be ignored when lib is present.
        Path other = store.resolve("other.jar");
        Files.writeString(other, "o");
        WorkerClasspath.writeSidecar(worker, List.of(other));

        String id = "jk-pref-test-" + System.nanoTime();
        try {
            WorkerLib.materialize(id, worker, List.of(dep));
            // pathsIfPresent uses id from jar name → jk-pref
            // materialize used custom id — align id with idFromWorkerJar
            // idFromWorkerJar("jk-pref-1.0.jar") → jk-pref
            WorkerLib.remove(id);
            WorkerLib.materialize(WorkerLib.idFromWorkerJar(worker), worker, List.of(dep));

            List<Path> cp = WorkerClasspath.paths(worker);
            assertThat(cp).hasSize(2);
            assertThat(cp.get(0).toString()).contains(WorkerLib.root().toString());
            assertThat(cp.get(0).getFileName().toString()).isEqualTo("jk-pref-1.0.jar");
            assertThat(cp.get(1).getFileName().toString()).isEqualTo("extra.jar");
            // Not the ugly sidecar path
            assertThat(cp).doesNotContain(other.toAbsolutePath().normalize());
        } finally {
            try {
                WorkerLib.remove(WorkerLib.idFromWorkerJar(worker));
            } catch (Exception ignored) {
                /* cleanup */
            }
        }
    }
}
