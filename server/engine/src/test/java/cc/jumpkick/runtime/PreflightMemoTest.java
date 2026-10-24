// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PreflightMemoFixtures.writeProject;
import static cc.jumpkick.runtime.PreflightMemoFixtures.writeWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Settled;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.testing.Symlinks;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The local dirty-set memo and the module fingerprints it keys on. */
class PreflightMemoTest {

    /** Long enough after its writes that every file this test wrote reads as {@link Settled}. */
    private static final Duration SETTLED = Duration.ofSeconds(5);

    @AfterEach
    void realClock() {
        Settled.aheadForTests(null);
    }

    @AfterEach
    void tidy() {
        SessionContext.reset();
        BuildIdentity.overrideBuildIdForTests(null);
    }

    /** A source that is a link is the target's bytes: an edit behind the link moves the fingerprint. */
    @Test
    void an_edit_behind_a_linked_source_moves_the_fingerprint(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path outside = Files.writeString(tmp.resolve("Shared.java"), "class Shared {}");
        Symlinks.create(tmp.resolve("src/main/java/Shared.java"), outside);
        PreflightMemo.Fingerprint before = PreflightMemo.fingerprintModule(tmp, false);
        Files.writeString(outside, "class Shared { int x; }");
        assertThat(PreflightMemo.fingerprintModule(tmp, false)).isNotEqualTo(before);
    }

    /** A link to a directory in a source root is not entered, so it vouches for nothing. */
    @Test
    void a_directory_link_in_a_source_root_is_uncertain(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path outside = Files.createDirectories(tmp.resolve("shared"));
        Files.writeString(outside.resolve("A.java"), "class A {}");
        Symlinks.create(tmp.resolve("src/main/java/shared"), outside);
        assertThat(PreflightMemo.fingerprintModule(tmp, false))
                .isInstanceOfSatisfying(
                        PreflightMemo.Uncertain.class,
                        u -> assertThat(u.reason()).contains("shared").contains("does not follow"));
    }

    @Test
    void producer_change_misses_memo_even_when_inputs_match(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        BuildIdentity.overrideBuildIdForTests("engine-aaaa");
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        BuildIdentity.overrideBuildIdForTests("engine-bbbb");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .as("a memo certified under another engine must not skip packaging re-key")
                .isEmpty();
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

    /**
     * A fixture is what proves a rule bites; editing one must re-plan like a rule edit, or the
     * proof never runs again and a fixture that stopped biting stays green.
     */
    @Test
    void a_fixture_edit_misses_the_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk-guards.toml"), """
                [guards.no-exit]
                kind       = "forbid"
                signatures = ["java.lang.System#exit"]
                instead    = "return an exit code"
                fixture    = "guard-fixtures/no-exit"
                why        = "w"
                """);
        Path bad =
                Files.createDirectories(tmp.resolve("guard-fixtures/no-exit")).resolve("Bad.java");
        Files.writeString(bad, "class Bad { void f() { System.exit(1); } }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(bad, "class Bad {}\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    /**
     * A profile's javac args key every compile step, so a memo certified clean by a default build
     * must not answer a {@code --profile} build, nor the other way round.
     */
    @Test
    void a_memo_stored_under_one_profile_is_a_miss_under_another(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Map<Path, String> fps = PreflightMemo.snapshotFingerprints(graph, false).fingerprints();

        PreflightMemo.storeDirty(tmp, graph, false, null, Set.of(), fps);
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false, null)).isPresent();
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false, "strict")).isEmpty();

        PreflightMemo.storeDirty(tmp, graph, false, "strict", Set.of(), fps);
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false, "strict")).isPresent();
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false, null)).isEmpty();
    }

    /**
     * A workspace that declares {@code [test] exclude-tags} never runs under
     * {@link TestSelection#DEFAULT}, and must still hit the memo it wrote: the header keys the
     * selection, so the matching run hits and a widened one misses. Refusing every selection but
     * the zero value made such a workspace pay the whole forecast walk on every build.
     */
    @Test
    void a_memo_stored_under_a_tag_selection_hits_it_and_misses_a_wider_one(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        TestSelection fastTier = TestSelection.of(List.of(), false, List.of(), List.of("slow"), true);
        TestSelection widened = TestSelection.of(List.of(), false, List.of("slow"), List.of(), true);

        SessionContext.runWhere(Session.defaults().withTestSelection(fastTier), () -> {
            Map<Path, String> fps =
                    PreflightMemo.snapshotFingerprints(graph, false).fingerprints();
            PreflightMemo.storeDirty(tmp, graph, false, null, Set.of(), fps);
            assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false, null))
                    .as("the selection that wrote the memo reads it back")
                    .isPresent();
        });
        SessionContext.runWhere(Session.defaults().withTestSelection(widened), () -> assertThat(
                        PreflightMemo.tryLoadDirty(tmp, graph, false, null))
                .as("a widened selection covers tests the stored clean claim did not")
                .isEmpty());
        SessionContext.runWhere(Session.defaults().withTestSelection(TestSelection.DEFAULT), () -> assertThat(
                        PreflightMemo.tryLoadDirty(tmp, graph, false, null))
                .as("the unfiltered default is itself a different selection")
                .isEmpty());
    }

    /**
     * The preflight, the test stamp and a second preflight all read the same files; the bytes of
     * each are read once per build and the rest are memo hits on the stat identity.
     */
    @Test
    void source_bytes_are_read_once_across_the_preflight_and_the_test_stamp(@TempDir Path tmp) throws Exception {
        Settled.aheadForTests(SETTLED);
        writeProject(tmp);
        Path testSrc = tmp.resolve("src/test/java/AppTest.java");
        Files.createDirectories(testSrc.getParent());
        Files.writeString(testSrc, "class AppTest {}\n");
        Path lock = tmp.resolve("jk-lock.toml");
        // The memo trusts a self-hash only once the file has settled past the mtime tick window.
        for (Path f : List.of(tmp.resolve("jk.toml"), lock, tmp.resolve("src/main/java/App.java"), testSrc)) {
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        }
        SessionContext.runWhere(Session.defaults().withCacheDir(tmp.resolve("cache")), () -> {
            FileHashMemo.reset();
            FileHashMemo.resetStats();
            assertThat(PreflightMemo.fingerprintModule(tmp, false)).isInstanceOf(PreflightMemo.Known.class);
            long readsAfterPreflight = FileHashMemo.contentReads();
            assertThat(readsAfterPreflight)
                    .as("manifest, lock and the two sources: each read once")
                    .isEqualTo(4);

            TestStamp.computeKey(
                    List.of(testSrc), tmp.resolve("target/classes"), List.of(), lock, List.of(), List.of());
            assertThat(FileHashMemo.contentReads())
                    .as("the stamp's test source and lock are memo hits")
                    .isEqualTo(readsAfterPreflight);

            PreflightMemo.fingerprintModule(tmp, false);
            assertThat(FileHashMemo.contentReads())
                    .as("a second preflight over the same tree reads no bytes")
                    .isEqualTo(readsAfterPreflight);
            assertThat(FileHashMemo.memoHits()).isGreaterThanOrEqualTo(6);
        });
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

    /**
     * A root declared outside {@code src/} is an input like any other: the memo must miss on an
     * edit there, or the module builds as up to date with a stale jar.
     */
    @Test
    void an_edit_under_a_build_extra_src_root_misses_the_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25

                [build]
                extra-src = ["grails-app/services"]
                """);
        Path bar = Files.createDirectories(tmp.resolve("grails-app/services")).resolve("Bar.java");
        Files.writeString(bar, "class Bar { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(bar, "class Bar { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .as("an edit under [build] extra-src is an input change")
                .isEmpty();
    }

    @Test
    void an_edit_under_a_test_extra_src_root_misses_the_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25

                [test]
                extra-src = ["shared-tests/java"]
                """);
        Path helper = Files.createDirectories(tmp.resolve("shared-tests/java")).resolve("Helper.java");
        Files.writeString(helper, "class Helper { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(helper, "class Helper { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .as("an edit under [test] extra-src is an input change")
                .isEmpty();
    }

    @Test
    void an_edit_under_a_fixtures_root_outside_src_misses_the_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25

                [test]
                fixtures = "fixtures/java"
                """);
        Path fixture = Files.createDirectories(tmp.resolve("fixtures/java")).resolve("Fx.java");
        Files.writeString(fixture, "class Fx { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isPresent();

        Files.writeString(fixture, "class Fx { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .as("an edit under [test] fixtures is an input change")
                .isEmpty();
    }

    /** A source that will not read is not a digest input; it is the reason the module is scheduled. */
    @Test
    void an_unreadable_source_is_an_uncertain_fingerprint_naming_the_file(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows());
        writeProject(tmp);
        Path src = tmp.resolve("src/main/java/App.java");
        Set<PosixFilePermission> was = Files.getPosixFilePermissions(src);
        Files.setPosixFilePermissions(src, Set.of());
        assumeFalse(Files.isReadable(src), "running as a user the permission bits do not bind");
        try {
            PreflightMemo.Fingerprint fp = PreflightMemo.fingerprintModule(tmp, false);
            assertThat(fp).isInstanceOf(PreflightMemo.Uncertain.class);
            PreflightMemo.Uncertain uncertain = (PreflightMemo.Uncertain) fp;
            assertThat(uncertain.input().getFileName().toString()).isEqualTo("App.java");
            assertThat(uncertain.reason()).contains("App.java").contains("AccessDeniedException");
        } finally {
            Files.setPosixFilePermissions(src, was);
        }
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
        Files.createDirectories(BuildLayout.moduleTargetDir(aDir));
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

    @Test
    void rewriting_the_resolved_jdk_release_file_misses_the_memo(@TempDir Path tmp) throws Exception {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path home = jdks.resolve("temurin-99.0.1");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Path release = home.resolve("release");
        Files.writeString(release, "JAVA_VERSION=\"99.0.1\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = "temurin-99"
                java = 25
                """);
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
        BuildGraph.Result graph =
                BuildGraph.resolve(project, JkBuildParser.parse(Files.readString(project.resolve("jk.toml"))));
        Session session = Session.defaults().withVariant("", Map.of("JK_JDKS_DIR", jdks.toString()));
        JdkEnsure.resetSharedRegistries();
        try {
            SessionContext.runWhere(session, () -> {
                storeDirty(project, graph, Set.of());
                assertThat(PreflightMemo.tryLoadDirty(project, graph, false))
                        .as("an unchanged release file still hits")
                        .isPresent();
            });
            Files.writeString(release, "JAVA_VERSION=\"99.0.2\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
            SessionContext.runWhere(session, () -> assertThat(PreflightMemo.tryLoadDirty(project, graph, false))
                    .as("the compile key hashes this release file, so the memo must miss")
                    .isEmpty());
        } finally {
            JdkEnsure.resetSharedRegistries();
        }
    }

    @Test
    void a_member_rules_edit_dirties_that_module_and_the_root_lane(@TempDir Path tmp) throws Exception {
        writeWorkspace(tmp);
        Files.writeString(tmp.resolve("jk-guards.toml"), "# root\n");
        Files.writeString(tmp.resolve("a/jk-guards.toml"), "member = \"a\"\n");
        Files.writeString(tmp.resolve("b/jk-guards.toml"), "member = \"b\"\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .get()
                .extracting("dirty")
                .isEqualTo(Set.of());

        Files.writeString(tmp.resolve("a/jk-guards.toml"), "member = \"edited\"\n");
        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        Path a = moduleDir(graph, "a");
        Path b = moduleDir(graph, "b");
        // The root's lanes load the merged set, so a member's edit reaches them too. b's do not.
        assertThat(hit.get().dirty())
                .containsExactlyInAnyOrder(a, tmp.toAbsolutePath().normalize())
                .doesNotContain(b);
    }

    @Test
    void editing_a_module_script_dirties_that_module(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path script = tmp.resolve("jk/before-compile.kts");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "println(1)\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .get()
                .extracting("dirty")
                .isEqualTo(Set.of());

        Files.writeString(script, "println(2)\n");
        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty())
                .containsExactly(
                        graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
    }

    @Test
    void an_always_script_stays_dirty_when_sources_match(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path script = tmp.resolve("jk/before-compile.kts");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "// jk: always\nprintln(1)\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());

        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty())
                .containsExactly(
                        graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
    }

    @Test
    void editing_a_non_source_file_dirties_a_root_anchor(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        Path script = tmp.resolve("jk/after-build.kts");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "println(1)\n");
        Path readme = tmp.resolve("README.md");
        Files.writeString(readme, "one\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        storeDirty(tmp, graph, Set.of());
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false))
                .get()
                .extracting("dirty")
                .isEqualTo(Set.of());

        Files.writeString(readme, "two\n");
        Optional<PreflightMemo.DirtyMemo> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get().dirty())
                .containsExactly(
                        graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
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

    private static Path moduleDir(BuildGraph.Result graph, String name) {
        return graph.topoOrder().stream()
                .map(u -> u.dir().toAbsolutePath().normalize())
                .filter(dir -> dir.getFileName().toString().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
