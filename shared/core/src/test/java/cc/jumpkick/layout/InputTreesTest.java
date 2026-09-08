// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    void covering_a_compact_module_lists_each_sibling_suites_src_and_not_the_sibling(@TempDir Path dir)
            throws Exception {
        // Compact layout: no src/main. Discovery asks <suite>/src; a sibling with no src/ (a workspace
        // root's clients/ or server/, with their Gradle output) must not be listed into the snapshot.
        Files.writeString(Files.createDirectories(dir.resolve("test/src")).resolve("T.java"), "class T {}");
        Files.writeString(Files.createDirectories(dir.resolve("demo/src")).resolve("D.java"), "class D {}");
        Path big = Files.createDirectories(dir.resolve("clients/cli/build/classes"));
        for (int i = 0; i < 5; i++) Files.writeString(big.resolve("C" + i + ".class"), "x");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));
        inRequest(() -> {
            InputTrees.coverModule(dir);
            InputTrees.finishJob();
        });
        Matcher m = Pattern.compile("\\\"nodes\\\":(\\d+)").matcher(InputTrees.lastStatusJson());
        assertThat(m.find()).isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("T.java and D.java under the suites' src; nothing under clients/")
                .isEqualTo(2);
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
        inRequest(() -> assertThat(InputTrees.growLimitBytes()).isEqualTo(192L * 1024 * 1024));
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 0));
        inRequest(() -> assertThat(InputTrees.growLimitBytes()).isZero());
    }

    @Test
    void off_a_request_nothing_is_retained_and_nothing_is_charged(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));

        // No ambient ledger: a pool thread that pre-dates the request, a CLI-side helper. There is
        // no finishJob for these, so a charge here would leak the pool for the engine's lifetime.
        for (int i = 0; i < 3; i++) {
            var snap = InputTrees.of(src);
            assertThat(snap.overflow()).as("unscoped callers live-walk").isTrue();
            assertThat(snap.withExtension(".java")).hasSize(1);
        }
        assertThat(InputTrees.poolUsedBytes()).isZero();
        assertThat(InputTrees.growLimitBytes()).isZero();

        // And the pool is still intact for a real job: it retains, then releases on finish.
        inRequest(() -> {
            InputTrees.coverModule(dir);
            assertThat(InputTrees.of(src).overflow()).isFalse();
            assertThat(InputTrees.poolUsedBytes()).isPositive();
        });
        assertThat(InputTrees.poolUsedBytes()).isZero();
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
    void cover_module_covers_only_dirs_discover_could_ask_about(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Files.createDirectories(dir.resolve("test"));
        // Discovery reads <suite>/src, so that is the covered root — never the sibling itself.
        Path integration = Files.createDirectories(dir.resolve("integration/src"));
        Files.writeString(integration.resolve("IT.java"), "class IT {}");
        // None of these can ever be a suite (dotted, capitalized, reserved-in-any-case) — covering
        // them would spend retain budget on trees no collector reads.
        Files.writeString(
                Files.createDirectories(dir.resolve(".github/workflows")).resolve("ci.yml"), "x");
        Files.writeString(Files.createDirectories(dir.resolve("Demo")).resolve("D.java"), "class D {}");
        Files.writeString(Files.createDirectories(dir.resolve("Docs")).resolve("d.md"), "x");

        inRequest(() -> {
            PathUtil.resetWalks();
            InputTrees.coverModule(dir);
            // src, test, the sibling listing itself, and the one real suite's src — nothing else.
            assertThat(PathUtil.walks()).isEqualTo(4);
            assertThat(InputTrees.of(integration).withExtension(".java")).hasSize(1);
            assertThat(PathUtil.walks()).as("the suite dir was pre-covered").isEqualTo(4);
        });
    }

    @Test
    void stream_only_queries_are_still_memoized_per_request(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(src.resolve("A" + i + ".java"), "class A" + i + " {}");
        }
        Files.writeString(src.resolve("B.kt"), "class B");
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 0));
        inRequest(() -> {
            PathUtil.resetWalks();
            for (int i = 0; i < 21; i++) {
                assertThat(InputTrees.of(src).withExtension(".java")).hasSize(5);
            }
            assertThat(PathUtil.walks()).as("21 streamed asks, one walk").isEqualTo(1);

            PathUtil.resetWalks();
            for (int i = 0; i < 5; i++) {
                assertThat(InputTrees.of(src).withExtensions(".java", ".kt")).hasSize(6);
            }
            assertThat(PathUtil.walks())
                    .as("multi-extension is one pass, memoized")
                    .isEqualTo(1);

            PathUtil.resetWalks();
            for (int i = 0; i < 5; i++) {
                assertThat(InputTrees.of(src).anyExtension(".kt")).isTrue();
            }
            assertThat(PathUtil.walks()).as("existence probes memoize too").isEqualTo(1);
        });
    }

    @Test
    void with_extensions_matches_the_union_in_walk_order(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Files.writeString(src.resolve("B.kt"), "class B");
        Files.writeString(src.resolve("c.txt"), "not source");
        inRequest(() -> {
            InputTrees.coverModule(dir);
            var snap = InputTrees.of(src);
            assertThat(snap.overflow()).isFalse();
            assertThat(snap.withExtensions(".java", ".kt"))
                    .containsExactlyInAnyOrder(
                            src.resolve("A.java").toAbsolutePath().normalize(),
                            src.resolve("B.kt").toAbsolutePath().normalize());
        });
    }

    @Test
    void concurrent_asks_for_one_root_walk_once_and_the_pool_balances(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java"));
        for (int i = 0; i < 20; i++) {
            Files.writeString(src.resolve("A" + i + ".java"), "class A" + i + " {}");
        }
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));
        inRequest(() -> {
            PathUtil.resetWalks();
            // Threads created inside the request inherit the ambient ledger and so share one
            // table — the shape of a job's lanes and tick suppliers.
            AtomicInteger sized = new AtomicInteger();
            Thread[] threads = new Thread[8];
            for (int t = 0; t < threads.length; t++) {
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < 25; i++) {
                        if (InputTrees.of(dir.resolve("src"))
                                        .withExtension(".java")
                                        .size()
                                == 20) {
                            sized.incrementAndGet();
                        }
                    }
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            assertThat(sized)
                    .as("every ask on every thread saw the full listing")
                    .hasValue(8 * 25);
            assertThat(PathUtil.walks()).as("one covering walk, all threads").isEqualTo(1);
        });
        assertThat(InputTrees.poolUsedBytes())
                .as("charges balanced by finishJob")
                .isZero();
    }

    @Test
    void an_unlistable_tree_streams_and_is_never_an_empty_covering_listing(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        Path extra = Files.createDirectories(dir.resolve("overlay"));
        Files.writeString(extra.resolve("B.java"), "class B {}");
        inRequest(() -> {
            InputTrees.loadFailureForTest = new IOException("readdir failed");
            var snap = InputTrees.of(src);
            InputTrees.loadFailureForTest = null;
            // Unknown, not empty: an authoritative empty Listing would answer "no files" for the
            // whole job and hand the module fingerprint a stable digest of nothing.
            assertThat(snap.overflow()).isTrue();
            assertThat(snap.withExtension(".java")).hasSize(1);
            // Not a budget event: the job still retains other roots.
            assertThat(InputTrees.of(extra).overflow()).isFalse();
            assertThat(InputTrees.poolUsedBytes()).isPositive();
        });
    }

    /** Build output is this job's own writing: a root under target/ is walked live, never retained. */
    @Test
    void roots_under_build_output_are_never_retained(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "[project]\nname='w'\n");
        Path generated = Files.createDirectories(dir.resolve("target/generated/ksp/java"));
        Files.writeString(generated.resolve("Gen.java"), "class Gen {}");
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        inRequest(() -> {
            var snap = InputTrees.of(generated);
            assertThat(snap.overflow()).as("live, not a retained Listing").isTrue();
            assertThat(snap.withExtension(".java")).hasSize(1);
            assertThat(InputTrees.poolUsedBytes()).isZero();
            writeQuietly(generated.resolve("Gen2.java"));
            assertThat(snap.withExtension(".java"))
                    .as("the same question at a later step has a new answer")
                    .hasSize(2);
            assertThat(InputTrees.of(generated).anyExtension(".kt")).isFalse();
            writeQuietly(generated.resolve("Gen3.kt"));
            assertThat(InputTrees.of(generated).anyExtension(".kt"))
                    .as("existence probes are not memoized either")
                    .isTrue();
            // Sources are still retained as before.
            assertThat(InputTrees.of(src).overflow()).isFalse();
        });
        assertThat(InputTrees.isBuildOutput(dir.resolve("target/x"))).isTrue();
        assertThat(InputTrees.isBuildOutput(dir.resolve("src/target-practice/x")))
                .isFalse();
    }

    /**
     * The manifest anchors the rule. A package named {@code target} under {@code src/} is the
     * user's input; a name-only rule refused the whole module's snapshot for it and walked the
     * tree live on every preflight.
     */
    @Test
    void a_directory_merely_named_target_is_not_build_output(@TempDir Path w) throws Exception {
        Files.writeString(w.resolve("jk.toml"), "[project]\nname='w'\n");
        Path pkg = Files.createDirectories(w.resolve("src/main/java/com/acme/target"));
        Files.writeString(pkg.resolve("T.java"), "class T {}");
        assertThat(InputTrees.isBuildOutput(pkg)).isFalse();
        assertThat(InputTrees.isBuildOutput(w.resolve("src/main/resources/target/x")))
                .isFalse();
        inRequest(() -> assertThat(InputTrees.of(w.resolve("src")).overflow())
                .as("the module's sources are retained like any other")
                .isFalse());
        assertThat(InputTrees.isBuildOutput(w.resolve("target/classes/main")))
                .as("the module's own output still is")
                .isTrue();
        assertThat(InputTrees.isBuildOutput(Path.of("/target/x")))
                .as("a root-level target has no owner")
                .isFalse();
    }

    /**
     * The scratch carve-out, which is why the fixtures above can be {@code @TempDir} at all: under
     * {@code jk test} a forked worker's temp root is {@code <module>/target/tmp/}, so a name-only
     * rule answered "build output" for every tree a test builds.
     */
    @Test
    void the_declared_scratch_root_is_not_this_jobs_writing(@TempDir Path w) throws Exception {
        Files.writeString(w.resolve("jk.toml"), "[workspace]\nmodules = ['shared/core']\n");
        // A fixture module a test builds inside the scratch root, with a manifest of its own.
        Path fixture = Files.createDirectories(w.resolve("target/tmp/junit123/fx"));
        Files.writeString(fixture.resolve("jk.toml"), "[project]\nname='fx'\n");
        assertThat(InputTrees.isBuildOutput(w.resolve("target/tmp/junit123/src")))
                .as("a forked test JVM's temp root is scratch, not output")
                .isFalse();
        assertThat(InputTrees.isBuildOutput(w.resolve("target/shared/core/tmp/junit123/src")))
                .as("a workspace member's scratch sits a module path deeper")
                .isFalse();
        assertThat(InputTrees.isBuildOutput(w.resolve("target/shared/core/tmp/w20/junit123/src")))
                .as("and deeper again once the worker pool splits it")
                .isFalse();
        assertThat(InputTrees.isBuildOutput(fixture.resolve("target/generated/ksp")))
                .as("a target/ tree inside a scratch tree is output again")
                .isTrue();
        assertThat(InputTrees.isBuildOutput(w.resolve("target/tmp/junit123/data/target/x")))
                .as("unless nothing owns it")
                .isFalse();
        assertThat(InputTrees.isBuildOutput(w.resolve("target/shared/core/classes/main")))
                .as("real module output is untouched")
                .isTrue();
        assertThat(InputTrees.isBuildOutput(w.resolve("target/tmpfiles/x")))
                .as("the reserved name, not every name starting with it")
                .isTrue();
    }

    /** The real shape of an unlistable tree: a subdirectory this process cannot open. */
    @Test
    void an_unopenable_subdirectory_streams_instead_of_covering_a_hole(@TempDir Path dir) throws Exception {
        assumeTrue(!Os.isWindows(), "POSIX permissions");
        Path src = Files.createDirectories(dir.resolve("src"));
        Path locked = Files.createDirectories(src.resolve("main/java"));
        Files.writeString(locked.resolve("A.java"), "class A {}");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "not running as root");
        try {
            inRequest(() -> {
                var snap = InputTrees.of(src);
                assertThat(snap.overflow())
                        .as("unknown, not an empty authoritative listing")
                        .isTrue();
            });
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void file_ref_carries_size_mtime_nanos_and_regular_bit(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        inRequest(() -> {
            InputTrees.coverModule(dir);
            InputTrees.FileRef ref = InputTrees.of(src).files().getFirst();
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
        assertThat(vfs).contains("\"misses\":");
        assertThat(vfs).contains("\"streamOnly\":false");
        String ack = InputTrees.appendToStatusAck("{\"type\":\"status-ack\"}");
        assertThat(ack).startsWith("{\"type\":\"status-ack\",\"vfs\":{");
        assertThat(ack).endsWith("}");
    }

    private static void writeQuietly(Path file) {
        try {
            Files.writeString(file, "generated");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
