// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.task.IoLedger;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local dirty-set, graph rebuild, and plan shape memos. */
class PreflightMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void store_then_load_hits_when_inputs_unchanged(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Set<Path> dirty =
                Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
        storeDirty(tmp, graph, dirty);

        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty()).isEqualTo(dirty);
    }

    /**
     * A guarded workspace salts every stored row with its rule files; the load must recompute
     * with the same salt or the memo misses on every build and the walk is paid each time.
     */
    @Test
    void a_guarded_workspace_stores_and_reloads_a_clean_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk-guards.toml"), "# rules\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());

        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit)
                .as("stored and recomputed rows carry the same guard salt")
                .isPresent();
        assertThat(hit.get().dirty()).isEmpty();

        // A rule edit must re-plan every module: the salt is an input, so the memo misses.
        Files.writeString(tmp.resolve("jk-guards.toml"), "# rules, edited\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void source_content_change_misses_memo_even_if_size_unchanged(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path src = tmp.resolve("src/main/java/App.java");
        Files.writeString(src, "class App { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());

        Files.writeString(src, "class App { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void resource_change_misses_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path res = tmp.resolve("src/main/resources");
        Files.createDirectories(res);
        Files.writeString(res.resolve("application.properties"), "answer=41\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(res.resolve("application.properties"), "answer=42\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void test_resource_change_misses_memo(@TempDir Path tmp) throws Exception {
        // Traditional fixture: test resources live under src/test/resources (under src/ walk).
        writeProject(tmp);
        Path res = tmp.resolve("src/test/resources");
        Files.createDirectories(res);
        Files.writeString(res.resolve("fixture.json"), "{}\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());

        Files.writeString(res.resolve("fixture.json"), "{\"a\":1}\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void simple_test_resource_change_misses_memo(@TempDir Path tmp) throws Exception {
        writeSimpleProject(tmp);
        Path res = tmp.resolve("test").resolve("resources");
        Files.createDirectories(res);
        Files.writeString(res.resolve("fixture.json"), "{}\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(res.resolve("fixture.json"), "{\"a\":1}\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void clean_row_with_missing_outputs_reports_restore_not_memo_miss(@TempDir Path tmp) throws Exception {
        // Inputs still match after a wiped module target — memo hits with restoreNeeded.
        writeWorkspace(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        // Create the layout target so the initial store sees outputs present for clean rows.
        Path aDir = graph.topoOrder().stream()
                .filter(u -> u.dir().endsWith("a"))
                .findFirst()
                .orElseThrow()
                .dir();
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp, aDir));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        deleteRecursively(tmp.resolve("target").resolve("a"));
        var hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty()).isEmpty();
        assertThat(hit.get().restoreNeeded()).isNotEmpty();
    }

    @Test
    void storeDirty_uses_snapshot_fingerprints_not_current_state(@TempDir Path tmp) throws Exception {
        // TOCTOU: a mid-build edit must not be recorded as clean by the post-build store.
        writeProject(tmp);
        Path src = tmp.resolve("src/main/java/App.java");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        var preBuild = PreflightMemo.snapshotFingerprints(graph, false);

        Files.writeString(src, "class App { int editedMidBuild; }\n"); // "mid-build" edit
        PreflightMemo.storeDirty(tmp, graph, false, Set.of(), preBuild.fingerprints());

        // The stored fingerprint is pre-edit, so the next preflight must miss and re-forecast.
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void forecastDirtyDirs_uses_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Session session = Session.defaults().withConfig(JkConfig.empty());
        Set<Path> first = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(PreflightMemo.memoFile(tmp)).exists();
        Set<Path> second = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void successful_build_overwrites_memo_with_all_clean(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(
                tmp,
                graph,
                Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize()));
        storeDirty(tmp, graph, Set.of());
        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty()).isEmpty();
    }

    @Test
    void graph_structure_memo_matches_after_store(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isFalse();
        PreflightMemo.storeGraph(tmp, graph);
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isTrue();
        assertThat(PreflightMemo.graphMemoFile(tmp)).exists();
    }

    @Test
    void tryLoadGraph_rebuilds_without_workspace_loader(@TempDir Path tmp) throws Exception {
        // Multi-module workspace so membership walk would otherwise load modules/
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        assertThat(full.hasErrors()).isFalse();
        assertThat(full.topoOrder()).hasSize(2);
        PreflightMemo.storeGraph(tmp, full);

        Optional<BuildGraph.Result> hit = PreflightMemo.tryLoadGraph(tmp);
        assertThat(hit).isPresent();
        BuildGraph.Result loaded = hit.get();
        assertThat(loaded.hasErrors()).isFalse();
        assertThat(loaded.topoOrder()).hasSize(2);
        assertThat(loaded.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList())
                .containsExactlyElementsOf(full.topoOrder().stream()
                        .map(BuildGraph.BuildUnit::coord)
                        .toList());
        // Edges: same prereq counts
        assertThat(loaded.edges().keySet()).hasSize(full.edges().keySet().size());
    }

    @Test
    void tryLoadGraph_misses_when_module_toml_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);

        Files.writeString(tmp.resolve("a/jk.toml"), """
                group = "t"
                name = "a"
                version = "0.2.0"
                jdk = 25
                java = 25
                """);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void tryLoadGraph_misses_when_root_gains_sources(@TempDir Path tmp) throws Exception {
        // Root buildability depends on it having sources, not on any toml — the structure key
        // must see the transition or the root module is silently never built.
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isPresent();

        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Root.java"), "class Root {}\n");
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void tryLoadGraph_misses_when_workspace_module_list_changes(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result full = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, full);
        // Drop b from the workspace list (folder still exists) — entry toml changes structure key.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["a"]
                """);
        assertThat(PreflightMemo.tryLoadGraph(tmp)).isEmpty();
    }

    @Test
    void resolve_uses_graph_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        var entry = JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml")));
        BuildGraph.Result first = BuildGraph.resolve(tmp, entry);
        PreflightMemo.storeGraph(tmp, first);
        // Second resolve should hit memo path (same structure)
        BuildGraph.Result second = BuildGraph.resolve(tmp, entry);
        assertThat(second.topoOrder().stream().map(BuildGraph.BuildUnit::coord).toList())
                .isEqualTo(first.topoOrder().stream()
                        .map(BuildGraph.BuildUnit::coord)
                        .toList());
    }

    @Test
    void shape_memo_round_trip(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        var shape = new PreflightMemo.BuildPlanShape(
                42,
                8,
                List.of(
                        new PreflightMemo.BuildPlanShape.StepShape("compile-java", "compile"),
                        new PreflightMemo.BuildPlanShape.StepShape("package-jar", "package")));
        PreflightMemo.storeShape(tmp, mod, false, shape);
        Optional<PreflightMemo.BuildPlanShape> hit = PreflightMemo.tryLoadShape(tmp, mod, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().weight()).isEqualTo(42);
        assertThat(hit.get().testWeight()).isEqualTo(8);
        assertThat(hit.get().steps()).hasSize(2);
        assertThat(hit.get().steps().getFirst().name()).isEqualTo("compile-java");
    }

    @Test
    void shape_memo_misses_when_toml_changes(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        PreflightMemo.storeShape(tmp, mod, false, new PreflightMemo.BuildPlanShape(10, 0, List.of()));
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "9.9.9"
                jdk = 25
                java = 25
                """);
        assertThat(PreflightMemo.tryLoadShape(tmp, mod, false)).isEmpty();
    }

    @Test
    void shape_memo_skip_tests_variants_coexist(@TempDir Path tmp) throws Exception {
        // fingerprint embeds skipTests — both rows must persist.
        writeProject(tmp);
        Path mod = tmp.toAbsolutePath().normalize();
        PreflightMemo.storeShape(
                tmp,
                mod,
                false,
                new PreflightMemo.BuildPlanShape(
                        40, 10, List.of(new PreflightMemo.BuildPlanShape.StepShape("run-tests", "test"))));
        PreflightMemo.storeShape(
                tmp,
                mod,
                true,
                new PreflightMemo.BuildPlanShape(
                        30, 0, List.of(new PreflightMemo.BuildPlanShape.StepShape("compile-java", "compile"))));
        Optional<PreflightMemo.BuildPlanShape> withTests = PreflightMemo.tryLoadShape(tmp, mod, false);
        Optional<PreflightMemo.BuildPlanShape> skipTests = PreflightMemo.tryLoadShape(tmp, mod, true);
        assertThat(withTests).isPresent();
        assertThat(withTests.get().weight()).isEqualTo(40);
        assertThat(withTests.get().testWeight()).isEqualTo(10);
        assertThat(skipTests).isPresent();
        assertThat(skipTests.get().weight()).isEqualTo(30);
        assertThat(skipTests.get().testWeight()).isEqualTo(0);
    }

    @Test
    void shape_memo_concurrent_upserts_retain_all_modules(@TempDir Path tmp) throws Exception {
        // parallel prepare must not drop peer rows.
        writeProject(tmp);
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("jk.toml"), """
                group = "t"
                name = "a"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        Files.writeString(b.resolve("jk.toml"), """
                group = "t"
                name = "b"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);
        var shapeA = new PreflightMemo.BuildPlanShape(11, 0, List.of());
        var shapeB = new PreflightMemo.BuildPlanShape(22, 0, List.of());
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 40; i++) PreflightMemo.storeShape(tmp, a, false, shapeA);
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 40; i++) PreflightMemo.storeShape(tmp, b, false, shapeB);
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertThat(PreflightMemo.tryLoadShape(tmp, a, false)).isPresent();
        assertThat(PreflightMemo.tryLoadShape(tmp, a, false).orElseThrow().weight())
                .isEqualTo(11);
        assertThat(PreflightMemo.tryLoadShape(tmp, b, false)).isPresent();
        assertThat(PreflightMemo.tryLoadShape(tmp, b, false).orElseThrow().weight())
                .isEqualTo(22);
    }

    @Test
    void costOf_from_shape_weights_matches_schedule_inputs(@TempDir Path tmp) {
        // ETA path builds ModuleCost without assembling a plan.
        var cost = EffortWeights.costOf(tmp, Set.of(), 100, 15);
        assertThat(cost.weight()).isEqualTo(100);
        assertThat(cost.testWeight()).isEqualTo(15);
        assertThat(cost.dir()).isEqualTo(tmp);
    }

    @Test
    void fingerprint_includes_simple_resources_dir(@TempDir Path tmp) throws Exception {
        writeSimpleProject(tmp);
        Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(tmp.resolve("resources/a.txt"), "v1");
        var fp1 = PreflightMemo.fingerprintModule(tmp, false);
        Files.writeString(tmp.resolve("resources/a.txt"), "v2");
        var fp2 = PreflightMemo.fingerprintModule(tmp, false);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprint_includes_compact_named_suite(@TempDir Path tmp) throws Exception {
        writeSimpleProject(tmp);
        Files.createDirectories(tmp.resolve("integration").resolve("src"));
        Files.writeString(tmp.resolve("integration/src/ITest.java"), "class ITest {}");
        var fp1 = PreflightMemo.fingerprintModule(tmp, false);
        Files.writeString(tmp.resolve("integration/src/ITest.java"), "class ITest { int x; }");
        var fp2 = PreflightMemo.fingerprintModule(tmp, false);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprint_includes_named_suite_resources(@TempDir Path tmp) throws Exception {
        writeSimpleProject(tmp);
        Files.createDirectories(tmp.resolve("integration").resolve("src"));
        Files.writeString(tmp.resolve("integration/src/ITest.java"), "class ITest {}");
        Files.createDirectories(tmp.resolve("integration").resolve("resources"));
        Files.writeString(tmp.resolve("integration/resources/fix.txt"), "a");
        var fp1 = PreflightMemo.fingerprintModule(tmp, false);
        Files.writeString(tmp.resolve("integration/resources/fix.txt"), "b");
        var fp2 = PreflightMemo.fingerprintModule(tmp, false);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    /**
     * An input that will not read is not an input change. The fingerprint says which file, the
     * snapshot files the module under uncertain, and no memo is written that would later claim
     * the module clean or dirty on a digest nobody took.
     */
    @Test
    void unreadable_lock_is_an_uncertain_fingerprint_naming_the_file(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows());
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Path lock = tmp.resolve("jk-lock.toml");
        Set<PosixFilePermission> was = Files.getPosixFilePermissions(lock);
        Files.setPosixFilePermissions(lock, Set.of());
        assumeFalse(Files.isReadable(lock), "running as a user the permission bits do not bind");
        try {
            PreflightMemo.Fingerprint fp = PreflightMemo.fingerprintModule(tmp, false);
            assertThat(fp).isInstanceOf(PreflightMemo.Uncertain.class);
            PreflightMemo.Uncertain uncertain = (PreflightMemo.Uncertain) fp;
            assertThat(uncertain.input().getFileName().toString()).isEqualTo("jk-lock.toml");
            assertThat(uncertain.reason())
                    .startsWith("the preflight could not read ")
                    .contains("jk-lock.toml")
                    .contains("AccessDeniedException");

            PreflightMemo.Snapshot snapshot = PreflightMemo.snapshotFingerprints(graph, false);
            assertThat(snapshot.fingerprints()).isEmpty();
            assertThat(snapshot.uncertain())
                    .containsOnlyKeys(tmp.toAbsolutePath().normalize());

            PreflightMemo.storeDirty(tmp, graph, false, Set.of(), snapshot.fingerprints());
            assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
            assertThat(PreflightMemo.resolveDirtyMemoFile(tmp)).isNull();
        } finally {
            Files.setPosixFilePermissions(lock, was);
        }
    }

    /** A memo that will not read is a miss that says so in the log, never an exception or a silent one. */
    @Test
    void unreadable_memo_file_is_a_miss_that_names_itself(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows());
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        Path memo = PreflightMemo.resolveDirtyMemoFile(tmp);
        assertThat(memo).isNotNull();
        Set<PosixFilePermission> was = Files.getPosixFilePermissions(memo);
        Files.setPosixFilePermissions(memo, Set.of());
        assumeFalse(Files.isReadable(memo), "running as a user the permission bits do not bind");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(log, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
            String written = log.toString(StandardCharsets.UTF_8);
            assertThat(written).contains("WARN  jk: preflight memo unreadable");
            assertThat(written).contains("memo=" + memo);
            assertThat(written).contains("cause=\"AccessDeniedException");
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
            Files.setPosixFilePermissions(memo, was);
        }
    }

    /** SIMPLE Mill-like fixture (layout=simple, no Maven src/main tree). */
    private static void writeSimpleProject(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/App.java"), "class App {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }

    /** Store with fingerprints snapshotted now — what every production call site does at preflight. */
    private static void storeDirty(Path entryDir, BuildGraph.Result graph, Set<Path> dirty) {
        PreflightMemo.storeDirty(
                entryDir,
                graph,
                false,
                dirty,
                PreflightMemo.snapshotFingerprints(graph, false).fingerprints());
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void fingerprint_is_identical_snapshotted_or_streamed(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(
                Files.createDirectories(tmp.resolve("src/main/resources")).resolve("app.properties"), "k=v\n");

        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 32));
        PreflightMemo.Fingerprint snapshotted = fingerprintInRequest(tmp);
        InputTrees.resetForTest();
        InputTrees.configureForTest(new JkEngineConfig(256, null, false, 0));
        PreflightMemo.Fingerprint streamed = fingerprintInRequest(tmp);
        InputTrees.resetForTest();

        // A module crossing the retain boundary between runs must not look edited.
        assertThat(snapshotted).isInstanceOf(PreflightMemo.Known.class).isEqualTo(streamed);
    }

    private static PreflightMemo.Fingerprint fingerprintInRequest(Path module) {
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            PreflightMemo.Fingerprint[] out = new PreflightMemo.Fingerprint[1];
            SessionContext.runWhere(
                    Session.defaults().withIo(ledger), () -> out[0] = PreflightMemo.fingerprintModule(module, false));
            return out[0];
        } finally {
            InputTrees.finishJob();
            IoLedger.close();
        }
    }

    private static void writeProject(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Path src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }

    private static void writeWorkspace(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(dir.resolve("target"));
        for (String m : new String[] {"a", "b"}) {
            Path md = dir.resolve(m);
            // The real layout: member outputs live under <workspace>/target/<rel>/, and
            // <member>/target is never created — the fixture must match production.
            Files.createDirectories(dir.resolve("target").resolve(m));
            Files.createDirectories(md.resolve("src/main/java"));
            Files.writeString(md.resolve("jk.toml"), """
                    group = "t"
                    name = "%s"
                    version = "0.1.0"
                    jdk = 25
                    java = 25
                    """.formatted(m));
            Files.writeString(md.resolve("src/main/java/M.java"), "class M {}\n");
            Files.writeString(md.resolve("jk-lock.toml"), """
                    version = 1
                    generated-by = "test"
                    resolution-algorithm = "pubgrub-v1"
                    """);
        }
    }
}
