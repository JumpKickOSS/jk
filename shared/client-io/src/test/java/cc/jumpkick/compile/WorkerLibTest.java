// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLibTest {

    @Test
    void id_from_m2_layout_and_filename() {
        Path m2 = Path.of(
                "/home/u/.jk/store/repos/local/cc/jumpkick/jk-kotlin-compiler/0.12.0/jk-kotlin-compiler-0.12.0.jar");
        assertThat(WorkerLib.idFromWorkerJar(m2)).isEqualTo("jk-kotlin-compiler");
        assertThat(WorkerLib.idFromWorkerJar(Path.of("/tmp/jk-test-runner-0.12.0.jar")))
                .isEqualTo("jk-test-runner");
        assertThat(WorkerLib.stripJarVersion("plugin-sdk-0.1.0.jar")).isEqualTo("plugin-sdk");
        assertThat(WorkerLib.looksLikeVersion("0.12.0")).isTrue();
        assertThat(WorkerLib.looksLikeVersion("libs")).isFalse();
    }

    @Test
    void snapshot_style_versions_parse_consistently() {
        // JK-1368: one parser for jar-name versions — id derivation and m2 placement agree on
        // multi-segment qualifiers.
        assertThat(WorkerLib.stripJarVersion("jk-foo-0.12.0-SNAPSHOT.jar")).isEqualTo("jk-foo");
        assertThat(WorkerLib.jarVersion("jk-foo-0.12.0-SNAPSHOT.jar")).isEqualTo("0.12.0-SNAPSHOT");
        assertThat(WorkerLib.jarVersion("jk-plugin-sdk-0.12.0.jar")).isEqualTo("0.12.0");
        assertThat(WorkerLib.jarVersion("plain-name.jar")).isNull();
        assertThat(WorkerLib.idFromWorkerJar(Path.of("/tmp/jk-foo-0.12.0-SNAPSHOT.jar")))
                .isEqualTo("jk-foo");
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

    /**
     * Source dir on the store filesystem so materialize hardlinks (like real installs); a tmpfs
     * {@code @TempDir} would copy-fall-back and never exercise the inode-match path.
     */
    private static Path storeSideSrc() throws Exception {
        Path src = WorkerLib.root().resolve(".test-src-" + System.nanoTime());
        Files.createDirectories(src);
        return src;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    /* cleanup */
                }
            });
        } catch (Exception ignored) {
            /* cleanup */
        }
    }

    @Test
    void stale_lib_is_ignored_for_a_newer_worker_jar() throws Exception {
        // JK-1349: lib dir materialized from v1 must not hijack a v2 launch of the same worker id.
        Path src = storeSideSrc();
        Path v1 = src.resolve("jk-stale-1.0.jar");
        Path v2 = src.resolve("jk-stale-2.0.jar");
        Files.writeString(v1, "v1-bytes");
        Files.writeString(v2, "v2-bytes");

        String id = WorkerLib.idFromWorkerJar(v1); // == idFromWorkerJar(v2) == "jk-stale"
        try {
            WorkerLib.materialize(id, v1, List.of());
            // The exact jar the lib was materialized from resolves through lib (inode match).
            List<Path> sameCp = WorkerLib.pathsIfPresent(v1);
            assertThat(sameCp).isNotNull();
            assertThat(sameCp.get(0).getFileName().toString()).isEqualTo("jk-stale-1.0.jar");
            // A different jar with the same id must fall back (null → sidecar path).
            assertThat(WorkerLib.pathsIfPresent(v2)).isNull();
            List<Path> cp = WorkerClasspath.paths(v2);
            assertThat(cp.get(0)).isEqualTo(v2.toAbsolutePath().normalize());
        } finally {
            try {
                WorkerLib.remove(id);
            } catch (Exception ignored) {
                /* cleanup */
            }
            deleteTree(src);
        }
    }

    @Test
    void override_jar_with_same_name_is_not_hijacked_by_lib() throws Exception {
        // JK-1349: -Djk.*.plugin.jar override — same filename, different file → lib must not win.
        Path src = storeSideSrc();
        Path installed = src.resolve("installed/jk-ovr-1.0.jar");
        Path override = src.resolve("custom/jk-ovr-1.0.jar");
        Files.createDirectories(installed.getParent());
        Files.createDirectories(override.getParent());
        Files.writeString(installed, "installed-bytes");
        Files.writeString(override, "override-bytes");

        String id = WorkerLib.idFromWorkerJar(installed);
        try {
            WorkerLib.materialize(id, installed, List.of());
            assertThat(WorkerLib.pathsIfPresent(installed)).isNotNull();
            assertThat(WorkerLib.pathsIfPresent(override)).isNull();
            assertThat(WorkerClasspath.paths(override).get(0))
                    .isEqualTo(override.toAbsolutePath().normalize());
        } finally {
            try {
                WorkerLib.remove(id);
            } catch (Exception ignored) {
                /* cleanup */
            }
            deleteTree(src);
        }
    }

    @Test
    void partial_or_foreign_lib_dirs_are_rejected() throws Exception {
        // JK-1353: no order file (e.g. an installed tool's bin dir) or a missing listed entry
        // (partial/damaged dir) must never resolve as a worker classpath.
        String id = "jk-partial-test-" + System.nanoTime();
        Path d = WorkerLib.dir(id);
        Files.createDirectories(d);
        try {
            Files.writeString(d.resolve("some-tool.jar"), "tool-bytes");
            assertThat(WorkerLib.pathsIfPresent(id)).isNull(); // no order file → not a worker dir

            Files.writeString(d.resolve(WorkerLib.ORDER_FILE), "# header\nsome-tool.jar\nmissing-dep.jar\n");
            assertThat(WorkerLib.pathsIfPresent(id)).isNull(); // listed entry absent → partial
        } finally {
            try {
                WorkerLib.remove(id);
            } catch (Exception ignored) {
                /* cleanup */
            }
        }
    }

    @Test
    void remove_deletes_the_lib_dir_entirely() throws Exception {
        Path src = storeSideSrc();
        Path worker = src.resolve("jk-rm-1.0.jar");
        Files.writeString(worker, "w");
        String id = "jk-rm-test-" + System.nanoTime();
        try {
            Path lib = WorkerLib.materialize(id, worker, List.of());
            assertThat(Files.isDirectory(lib)).isTrue();
            WorkerLib.remove(id);
            assertThat(Files.exists(lib)).isFalse(); // dir itself gone → CAS inodes unpinned
        } finally {
            deleteTree(src);
        }
    }

    @Test
    void worker_classpath_prefers_lib_when_materialized() throws Exception {
        // Store-side sources: real installs hardlink store → store/lib on one filesystem, and the
        // JK-1349 inode guard only accepts a lib dir materialized from the exact jar launched.
        Path src = storeSideSrc();
        Path worker = src.resolve("w/jk-pref-1.0.jar");
        Path dep = src.resolve("d/extra.jar");
        Files.createDirectories(worker.getParent());
        Files.createDirectories(dep.getParent());
        Files.writeString(worker, "w");
        Files.writeString(dep, "d");
        // Sidecar points at a third path that should be ignored when lib is present.
        Path other = src.resolve("other.jar");
        Files.writeString(other, "o");
        WorkerClasspath.writeSidecar(worker, List.of(other));

        try {
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
            deleteTree(src);
        }
    }
}
