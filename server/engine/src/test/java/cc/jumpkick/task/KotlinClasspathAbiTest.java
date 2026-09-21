// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Kotlin compile keys its classpath on each entry's ABI snapshot digest, memoized by content:
 * the snapshotter is asked once per distinct bytes, a dependency whose bytes moved but whose
 * snapshot did not keeps the key, and an entry no snapshot describes keys on its content.
 */
class KotlinClasspathAbiTest {

    /** Answers with a digest derived from a per-test "ABI" of each entry; records what it was asked. */
    private static final class FakeSnapshotter implements KotlinClasspathAbi.Snapshotter {
        final Map<Path, String> abiByEntry = new LinkedHashMap<>();
        final List<List<Path>> calls = new ArrayList<>();

        FakeSnapshotter abi(Path entry, String abi) {
            abiByEntry.put(entry.toAbsolutePath().normalize(), abi);
            return this;
        }

        @Override
        public Map<Path, String> snapshot(List<Path> entries) {
            calls.add(List.copyOf(entries));
            Map<Path, String> out = new LinkedHashMap<>();
            for (Path e : entries) {
                String abi = abiByEntry.get(e.toAbsolutePath().normalize());
                if (abi != null) out.put(e.toAbsolutePath().normalize(), Hashing.sha256Hex(abi));
            }
            return out;
        }
    }

    @Test
    void the_token_is_the_snapshot_digest(@TempDir Path dir) throws Exception {
        Path jar = write(dir.resolve("lib.jar"), "bytes v1");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(jar, "api A");

        String token = KotlinClasspathAbi.token(jar, snapshotter);

        assertThat(token).isEqualTo(KotlinClasspathAbi.PREFIX + Hashing.sha256Hex("api A"));
    }

    @Test
    void the_same_bytes_at_two_paths_are_snapshotted_once_and_share_the_token(@TempDir Path dir) throws Exception {
        Path a = write(dir.resolve("a/lib.jar"), "identical bytes");
        Path b = write(dir.resolve("b/lib.jar"), "identical bytes");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(a, "api").abi(b, "api");

        withCache(dir.resolve("cache"), () -> {
            List<String> tokens = KotlinClasspathAbi.tokens(List.of(a, b), snapshotter);
            assertThat(tokens.get(0)).startsWith(KotlinClasspathAbi.PREFIX).isEqualTo(tokens.get(1));
            assertThat(snapshotter.calls)
                    .as("one call, one entry: content identity dedups")
                    .hasSize(1);
            assertThat(snapshotter.calls.getFirst()).hasSize(1);

            // A later sighting of the same bytes is a memo hit: the worker is not asked again.
            KotlinClasspathAbi.token(b, snapshotter);
            assertThat(snapshotter.calls).hasSize(1);
        });
    }

    @Test
    void rewritten_bytes_with_the_same_snapshot_keep_the_token_and_a_moved_abi_changes_it(@TempDir Path dir)
            throws Exception {
        Path jar = write(dir.resolve("lib.jar"), "bytes v1");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(jar, "api A");

        withCache(dir.resolve("cache"), () -> {
            String before = KotlinClasspathAbi.token(jar, snapshotter);
            write(jar, "bytes v2 — a body changed, the ABI did not");
            String bodyOnly = KotlinClasspathAbi.token(jar, snapshotter);
            snapshotter.abi(jar, "api B");
            write(jar, "bytes v3 — a public signature changed");
            String apiMoved = KotlinClasspathAbi.token(jar, snapshotter);

            assertThat(bodyOnly).isEqualTo(before);
            assertThat(apiMoved).isNotEqualTo(before);
            assertThat(snapshotter.calls)
                    .as("each new content identity is snapshotted once")
                    .hasSize(3);
        });
    }

    @Test
    void an_entry_the_snapshotter_does_not_report_keys_on_content_and_is_not_memoized(@TempDir Path dir)
            throws Exception {
        Path jar = write(dir.resolve("lib.jar"), "bytes");
        FakeSnapshotter snapshotter = new FakeSnapshotter(); // reports nothing

        withCache(dir.resolve("cache"), () -> {
            String first = KotlinClasspathAbi.token(jar, snapshotter);
            String second = KotlinClasspathAbi.token(jar, snapshotter);

            assertThat(first).isEqualTo(ClasspathFingerprint.entry(jar)).startsWith("file:");
            assertThat(second).isEqualTo(first);
            assertThat(snapshotter.calls)
                    .as("asked again: a fallback is not remembered as an answer")
                    .hasSize(2);
        });
    }

    @Test
    void a_missing_entry_is_missing_without_asking(@TempDir Path dir) throws Exception {
        FakeSnapshotter snapshotter = new FakeSnapshotter();

        String token = KotlinClasspathAbi.token(dir.resolve("gone.jar"), snapshotter);

        assertThat(token).startsWith("missing:");
        assertThat(snapshotter.calls).isEmpty();
    }

    @Test
    void the_read_only_snapshotter_never_snapshots_and_keys_on_content(@TempDir Path dir) throws Exception {
        Path jar = write(dir.resolve("lib.jar"), "bytes");

        assertThat(KotlinClasspathAbi.token(jar, KotlinClasspathAbi.MEMOIZED_ONLY))
                .isEqualTo(ClasspathFingerprint.entry(jar));
    }

    @Test
    void the_kotlinc_key_follows_the_classpath_abi_not_its_bytes(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("App.kt"), "fun main() {}");
        Path worker = write(dir.resolve("worker.jar"), "worker");
        Path lib = write(dir.resolve("lib.jar"), "lib v1");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(lib, "api A");

        withCache(dir.resolve("cache"), () -> {
            String before = ActionKey.forKotlinc("compile-kotlin", request(src, lib, worker), "jk", snapshotter);
            write(lib, "lib v2 — body only");
            String bodyOnly = ActionKey.forKotlinc("compile-kotlin", request(src, lib, worker), "jk", snapshotter);
            snapshotter.abi(lib, "api B");
            write(lib, "lib v3 — new public function");
            String apiMoved = ActionKey.forKotlinc("compile-kotlin", request(src, lib, worker), "jk", snapshotter);

            assertThat(bodyOnly)
                    .as("a body-only rewrite of a dependency is a cache hit")
                    .isEqualTo(before);
            assertThat(apiMoved).as("an ABI change misses").isNotEqualTo(before);
        });
    }

    @Test
    void the_worker_closure_still_keys_on_content(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("App.kt"), "fun main() {}");
        Path lib = write(dir.resolve("lib.jar"), "lib");
        Path worker = write(dir.resolve("worker.jar"), "compiler 2.4.10");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(lib, "api");

        withCache(dir.resolve("cache"), () -> {
            String before = ActionKey.forKotlinc("compile-kotlin", request(src, lib, worker), "jk", snapshotter);
            write(worker, "compiler 2.4.20");
            String bumped = ActionKey.forKotlinc("compile-kotlin", request(src, lib, worker), "jk", snapshotter);

            assertThat(bumped).as("a different compiler is a different compile").isNotEqualTo(before);
        });
    }

    @Test
    void the_recorded_inputs_name_each_entry_by_its_abi_token(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("App.kt"), "fun main() {}");
        Path worker = write(dir.resolve("worker.jar"), "worker");
        Path lib = write(dir.resolve("lib.jar"), "lib");
        FakeSnapshotter snapshotter = new FakeSnapshotter().abi(lib, "api A");

        withCache(dir.resolve("cache"), () -> {
            Map<String, String> inputs = ActionKey.kotlincInputs(request(src, lib, worker), snapshotter);

            assertThat(inputs)
                    .containsEntry(
                            "cp:" + PortablePath.of(lib), KotlinClasspathAbi.PREFIX + Hashing.sha256Hex("api A"));
            assertThat(inputs).containsKeys(PortablePath.of(src), "jvmTarget", "jdk", "args");
        });
    }

    private static KotlincRequest request(Path src, Path lib, Path worker) {
        return KotlincRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(lib))
                .outputDir(src.resolveSibling("out"))
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .javaHome(Path.of(System.getProperty("java.home")))
                .build();
    }

    private interface Body {
        void run() throws Exception;
    }

    /** A session with a cache dir, so the ABI memo is live; memos and stats start empty. */
    private static void withCache(Path cache, Body body) {
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            AbiMemo.reset();
            FileHashMemo.reset();
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
