// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.SelfNukeCommand.Target;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs against a per-test {@code JK_HOME}/{@code JK_STATE_DIR} overlay ({@code jk.env.*} seam,
 * same as {@link cc.jumpkick.cli.engine.IsolatedRootsExtension}) — NOT the suite-shared home.
 * These tests genuinely nuke the store and stub a nested engine under {@code lib/jk-engine/};
 * against the shared home that wiped the CAS/worker libs and poisoned every later class's nested
 * engine spawn with a stub jar (mass exit-70s across the integration phase).
 */
class SelfNukeCommandTest {

    @org.junit.jupiter.api.io.TempDir
    Path isolatedHome;

    private String prevHome;
    private String prevState;

    @org.junit.jupiter.api.BeforeEach
    void isolateHome() throws IOException {
        prevHome = System.getProperty("jk.env.JK_HOME");
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_HOME", isolatedHome.toString());
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(isolatedHome.resolve("state")).toString());
        // self nuke is engine-hosted: give the isolated home a REAL launchable engine by
        // copying the suite home's materialized install (EngineTestExtension ran beforeAll,
        // before this overlay). A stub jar here just reproduces "no build engine" (exit 1).
        String suiteHome = System.getenv("JK_HOME");
        if (suiteHome != null && !suiteHome.isBlank()) {
            Path fromLib = Path.of(suiteHome).resolve("data").resolve("lib").resolve("jk-engine");
            Path toLib = Files.createDirectories(
                    isolatedHome.resolve("data").resolve("lib").resolve("jk-engine"));
            if (Files.isDirectory(fromLib)) {
                try (var stream = Files.list(fromLib)) {
                    for (Path p : stream.toList()) {
                        if (Files.isRegularFile(p)) {
                            Files.copy(p, toLib.resolve(p.getFileName().toString()));
                        }
                    }
                }
            }
            Path fromCfg = Path.of(suiteHome).resolve("config").resolve("jk-engine");
            Path toCfg = Files.createDirectories(isolatedHome.resolve("config").resolve("jk-engine"));
            if (Files.isDirectory(fromCfg)) {
                try (var stream = Files.list(fromCfg)) {
                    for (Path p : stream.toList()) {
                        if (Files.isRegularFile(p)) {
                            Files.copy(p, toCfg.resolve(p.getFileName().toString()));
                        }
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreHome() {
        if (prevHome == null) System.clearProperty("jk.env.JK_HOME");
        else System.setProperty("jk.env.JK_HOME", prevHome);
        if (prevState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevState);
    }

    /** A store/cache nuke that fails the way an unstartable engine fails, and counts being asked. */
    private static final class BrokenEngine implements SelfNukeCommand.Hosted {
        int calls;

        @Override
        public int storage() throws IOException {
            calls++;
            throw new IOException("could not start the build engine");
        }

        @Override
        public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped)
                throws IOException {
            calls++;
            throw new IOException("could not start the build engine");
        }
    }

    @Test
    void an_unreachable_engine_still_removes_everything_that_needs_no_engine() throws Exception {
        // The store and cache nukes run engine-side; state, config and the data root's own children
        // do not. An engine that cannot start used to unwind the whole command, so a user who had
        // just approved a table of paths got an error about the engine and every path still there
        // (JK-2015).
        JkDirs dirs = JkDirs.current();
        Path config = dirs.configDir().resolve("config.toml");
        Path versions = dirs.dataDir().resolve("versions");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "x = 1");
        Files.createDirectories(versions);
        Files.writeString(versions.resolve("v.toml"), "v");

        int exit = capture(() -> runNuke(new BrokenEngine(), true));

        assertThat(exit).as("a partial nuke must not report success").isNotZero();
        assertThat(config).as("config needs no engine to delete").doesNotExist();
        assertThat(versions).as("data-root children need no engine to delete").doesNotExist();
    }

    @Test
    void an_unreachable_engine_names_what_it_could_not_remove() throws Exception {
        // Silence here is the actual harm: the paths that survive are exactly the ones the user
        // cannot see, so the command has to say which targets it left behind.
        Files.createDirectories(JkDirs.current().configDir());
        Files.writeString(JkDirs.current().configDir().resolve("config.toml"), "x = 1");

        String err = captureText(() -> runNuke(new BrokenEngine(), true));

        assertThat(err).contains("NOT removed");
        assertThat(err).contains("artifact store");
        assertThat(err).contains("cache tier");
    }

    @Test
    void a_dry_run_touches_nothing_and_never_starts_an_engine() throws Exception {
        // The store preview walks the tree engine-side, so a dry run skips it outright — the
        // daemon it would spawn writes logs, an AOT index and a JDK registry into the state dir it
        // is pretending to delete. The cache preview is a purely local walk, so the dry run keeps
        // it, and even a failing preview must not turn the dry run red or write anything.
        Path config = JkDirs.current().configDir().resolve("config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "x = 1");
        long before = countFiles(isolatedHome);

        var hosted = new SelfNukeCommand.Hosted() {
            boolean storageAsked;
            Boolean cacheDryRun;

            @Override
            public int storage() {
                storageAsked = true;
                return 0;
            }

            @Override
            public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
                cacheDryRun = dryRun;
                return 0;
            }
        };
        int exit = capture(() -> runNuke(hosted, false));

        assertThat(exit).isZero();
        assertThat(hosted.storageAsked)
                .as("the store preview would spawn a daemon")
                .isFalse();
        assertThat(hosted.cacheDryRun)
                .as("the cache preview is local and must run, as a dry run")
                .isEqualTo(Boolean.TRUE);
        assertThat(config).exists();
        assertThat(countFiles(isolatedHome)).as("a dry run created files").isEqualTo(before);
    }

    private static long countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Roots deliberately kept: the nuke must never schedule them. Keyed by {@link JkDirs} accessor
     * name so the failure message can name the method an author needs to look at.
     */
    private static final Set<String> KEPT_ROOTS =
            Set.of("binDirectory", "jdksDir", "productLibDir", "binDir", "jdks", "productLib");

    /**
     * Roots deleted child-by-child rather than as one row, so the root itself is never scheduled
     * but everything under it (minus {@link SelfNukeCommand.Guards}) is.
     */
    private static final Set<String> ENUMERATED_ROOTS = Set.of("dataDir", "data");

    /**
     * Every directory jk owns is either nuked or deliberately kept — and adding a new one to
     * {@link JkDirs} without deciding which fails here.
     *
     * <p>Within a root the nuke is already exhaustive: cache, state, builds, tmp and config are
     * removed whole-tree, the store is removed whole-tree engine-side, and the data root is
     * enumerated child-by-child against the guards. What was *not* whitelist-shaped is the set of
     * roots itself — a hand-maintained list that a fourteenth accessor would silently fall out of,
     * leaving files behind that a user who ran {@code --all} believes are gone.
     */
    @Test
    void every_JkDirs_root_is_either_nuked_or_deliberately_kept() throws Exception {
        JkDirs dirs = JkDirs.current();
        // plan() enumerates the data root's children off disk, so it has to exist to be walked.
        Files.createDirectories(dirs.dataDir());
        Files.createDirectories(dirs.configDir());
        List<Path> planned = SelfNukeCommand.plan(dirs, EnumSet.allOf(Target.class)).stream()
                .map(SelfNukeCommand.PurgeRow::path)
                .toList();

        List<String> unaccounted = new ArrayList<>();
        for (Method m : JkDirs.class.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != Path.class) continue;
            String name = m.getName();
            if (KEPT_ROOTS.contains(name) || ENUMERATED_ROOTS.contains(name)) continue;
            // Statics answer through JkDirs.current(), which reads the same jk.env overlay the
            // test's `dirs` came from — so a static-only accessor (the common shape in JkDirs)
            // is walked like everything else instead of slipping past the tripwire.
            Object receiver = Modifier.isStatic(m.getModifiers()) ? null : dirs;
            Path root = ((Path) m.invoke(receiver)).toAbsolutePath().normalize();
            boolean covered = planned.stream().anyMatch(row -> root.equals(row) || root.startsWith(row));
            if (!covered) unaccounted.add(name + "() -> " + root);
        }

        assertThat(unaccounted)
                .as("JkDirs grew a root that `jk self nuke --all` neither deletes nor keeps on"
                        + " purpose. Add a row for it in SelfNukeCommand.plan(), or name it in"
                        + " KEPT_ROOTS/ENUMERATED_ROOTS here with the reason it survives.")
                .isEmpty();
    }

    @Test
    void the_kept_roots_are_actually_kept() throws Exception {
        JkDirs dirs = JkDirs.current();
        Files.createDirectories(dirs.dataDir());
        List<Path> planned = SelfNukeCommand.plan(dirs, EnumSet.allOf(Target.class)).stream()
                .map(SelfNukeCommand.PurgeRow::path)
                .toList();

        for (String name : KEPT_ROOTS) {
            Path root = ((Path) JkDirs.class.getMethod(name).invoke(dirs))
                    .toAbsolutePath()
                    .normalize();
            assertThat(planned)
                    .as("%s() is on the keep list but was scheduled for deletion", name)
                    .noneMatch(row -> root.equals(row) || root.startsWith(row));
        }
    }

    @Test
    void wipeRoots_never_includes_bin_jdks_or_the_product_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        Path productLib = dirs.productLibDir().toAbsolutePath().normalize();
        Files.createDirectories(productLib);

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs);
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            assertThat(abs).isNotEqualTo(bin);
            assertThat(abs).isNotEqualTo(jdks);
            assertThat(abs).isNotEqualTo(productLib);
            assertThat(abs.startsWith(bin)).isFalse();
            assertThat(abs.startsWith(jdks)).isFalse();
            assertThat(abs.startsWith(productLib)).isFalse();
        }
    }

    /**
     * {@code <store>/lib} is <em>not</em> guarded, and the name of the old assertion said it was.
     * The store row is delegated whole-tree to {@code jk storage nuke}, so everything under the
     * store goes with it — {@code lib} included. Nothing writes there today; if something starts
     * to, this test is where the decision gets revisited.
     */
    @Test
    void the_store_row_is_an_ancestor_of_store_lib_so_it_goes_with_the_store() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path storeLib = dirs.storeDir().resolve("lib").toAbsolutePath().normalize();
        Files.createDirectories(storeLib.resolve("jk-java-compiler"));
        Files.createDirectories(dirs.dataDir());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));

        assertThat(roots).anyMatch(storeLib::startsWith);
    }

    @Test
    void data_target_takes_the_store_root_plus_every_other_data_child() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir().toAbsolutePath().normalize();
        Path data = dirs.dataDir().toAbsolutePath().normalize();
        Files.createDirectories(store.resolve("sha256"));
        Files.createDirectories(dirs.storeDir().resolve("lib"));
        Files.createDirectories(data.resolve("android-sdk"));
        Files.createDirectories(data.resolve("completions"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        assertThat(roots).containsExactlyInAnyOrder(store, data.resolve("android-sdk"), data.resolve("completions"));
    }

    @Test
    @Tag("integration")
    void data_nuke_wipes_store_and_data_children_keeps_engine_credentials_and_bin() throws Exception {
        JkDirs dirs = JkDirs.current();
        // The isolated home carries a REAL materialized engine (isolateHome copy) — the hosted
        // nuke needs it to run, and its survival is exactly what this test asserts.
        Path engineHome = dirs.productLibDir().resolve("jk-engine");
        Path cas = dirs.storeDir().resolve("sha256");
        Path lib = dirs.storeDir().resolve("lib").resolve("jk-java-compiler");
        Path bin = dirs.binDirectory();
        Path creds = dirs.dataDir().resolve("credentials");
        Path repoCreds = dirs.dataDir().resolve("repo-credentials");
        Path completions = dirs.dataDir().resolve("completions");
        Files.createDirectories(engineHome);
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("plugin.jar"), "plugin");
        // A Maven-layout jar under repos/, because an EMPTY store is the shape that passes for the
        // wrong reason: this suite once went green only because a network outage left nothing here
        // for a worker to open, and the wipe therefore had nothing locked to trip over.
        Path repoJar = dirs.storeDir().resolve("repos/central/com/example/demo/1.0/demo-1.0.jar");
        Files.createDirectories(repoJar.getParent());
        Files.writeString(repoJar, "jar");
        Files.createDirectories(bin);
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");
        Files.createDirectories(creds);
        Files.writeString(creds.resolve("github.json"), "{}");
        Files.createDirectories(repoCreds);
        Files.writeString(repoCreds.resolve("central.json"), "{}");
        Files.createDirectories(completions);
        Files.writeString(completions.resolve("zsh"), "#compdef jk");

        // --store is the hidden pre-widening alias for --data; exercise it here.
        int exit = capture(() -> Jk.execute("self", "nuke", "--store", "-y"));
        assertThat(exit).isZero();
        // storage nuke: entire store, including plugin lib
        assertThat(cas.resolve("ab/blob")).doesNotExist();
        assertThat(lib.resolve("plugin.jar")).doesNotExist();
        assertThat(repoJar)
                .as("the Maven-layout tree goes too — this is what a worker holds open")
                .doesNotExist();
        // the rest of the data root goes with it
        assertThat(completions).doesNotExist();
        // product-lib engine, credentials, and PATH survive
        assertThat(engineHome).isDirectory();
        assertThat(creds.resolve("github.json")).exists();
        assertThat(repoCreds.resolve("central.json")).exists();
        assertThat(foreign).exists();
    }

    /**
     * Every row of the confirm table, not one of them. The "Path to Delete" column may only list
     * paths that are gone <em>as directories</em> afterwards; JK-2455 made that true for the cache
     * and state roots and left the artifact store emptied-but-standing, which is the same table
     * saying "delete" and meaning "empty". {@code wipeRoots} is the row set the table prints for
     * {@code --data}: the delegated store row plus every unguarded child of the data root.
     */
    @Test
    @Tag("integration")
    void data_nuke_removes_every_path_the_confirm_table_listed() throws Exception {
        JkDirs dirs = JkDirs.current();
        Files.createDirectories(dirs.productLibDir().resolve("jk-engine"));
        Path cas = dirs.storeDir().resolve("sha256/ab");
        Files.createDirectories(cas);
        Files.writeString(cas.resolve("blob"), "cas");
        Path tools = dirs.storeDir().resolve("lib").resolve("jk-java-compiler"); // <store>/lib — a child of the store
        Files.createDirectories(tools);
        Files.writeString(tools.resolve("plugin.jar"), "plugin");
        Files.createDirectories(dirs.dataDir().resolve("completions"));
        Files.writeString(dirs.dataDir().resolve("completions/zsh"), "#compdef jk");
        Files.createDirectories(dirs.dataDir().resolve("android-sdk"));
        Path creds = dirs.dataDir().resolve("credentials");
        Files.createDirectories(creds);
        Files.writeString(creds.resolve("github.json"), "{}");
        Path repoCreds = dirs.dataDir().resolve("repo-credentials");
        Files.createDirectories(repoCreds);
        Files.writeString(repoCreds.resolve("central.json"), "{}");
        Files.createDirectories(dirs.binDirectory());
        Path foreign = dirs.binDirectory().resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        // Captured before the nuke: planData enumerates the data root's children as they are now.
        List<Path> tableRows = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        assertThat(tableRows).contains(dirs.storeDir().toAbsolutePath().normalize());

        assertThat(capture(() -> Jk.execute("self", "nuke", "--data", "-y"))).isZero();

        for (Path row : tableRows) {
            assertThat(row).as("row printed under Path to Delete: %s", row).doesNotExist();
        }
        // The guards the table promises to keep, and every one of them is a sibling of the store.
        assertThat(dirs.productLibDir().resolve("jk-engine")).isDirectory();
        assertThat(creds.resolve("github.json")).exists();
        assertThat(repoCreds.resolve("central.json")).exists();
        assertThat(foreign).as("PATH binaries are never a row").exists();
    }

    /**
     * The STATE rows delete the sockets and AOT cache an engine holds open, so a stopped fleet is
     * a precondition of that delete, not a courtesy performed once at the top. {@code jk storage
     * nuke} does its wipe engine-side: the {@code wipe-store} request calls {@code ensureRunning}
     * and the engine it boots outlives the call. That engine was still there when the STATE rows
     * went, and it wrote the state dir straight back under a command that had reported it deleted.
     */
    @Test
    @Tag("integration")
    void data_and_state_nuke_leaves_no_engine_to_write_the_state_dir_back() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cas = dirs.storeDir().resolve("sha256");
        Files.createDirectories(dirs.productLibDir().resolve("jk-engine"));
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(dirs.stateDir().resolve("aot"));
        Files.writeString(dirs.stateDir().resolve("aot/marker"), "aot");

        int exit = capture(() -> Jk.execute("self", "nuke", "--data", "--state", "-y"));

        assertThat(exit).isZero();
        assertThat(EngineFleet.listThisHome()).isEmpty();
        assertThat(dirs.stateDir()).doesNotExist();
    }

    @Test
    void data_flag_keeps_store_as_a_hidden_alias_on_the_same_key() {
        var opt = new SelfNukeCommand()
                .options().stream().filter(o -> o.matches("--data")).findFirst().orElseThrow();
        assertThat(opt.canonicalName()).isEqualTo("data");
        assertThat(opt.matches("--store")).isTrue();
        assertThat(opt.names()).doesNotContain("--store"); // alias: parsed, never shown in help
    }

    @Test
    void cache_only_selects_cache_dir() {
        JkDirs dirs = JkDirs.current();
        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.CACHE));
        assertThat(roots).containsExactly(dirs.cacheDir().toAbsolutePath().normalize());
    }

    @Test
    void cache_and_state_nuke_leaves_bin_alone() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Path state = dirs.stateDir();
        Path bin = dirs.binDirectory();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/marker"), "x");
        Files.createDirectories(state.resolve("aot"));
        Files.writeString(state.resolve("aot/marker"), "y");
        Files.createDirectories(bin);
        Path jkBin = bin.resolve("jk");
        if (!Files.exists(jkBin)) Files.writeString(jkBin, "#!/bin/sh\n");
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        int exit = capture(() -> Jk.execute("self", "nuke", "--cache", "--state", "-y"));
        assertThat(exit).isZero();
        assertThat(Files.exists(cache.resolve("actions/marker"))).isFalse();
        assertThat(Files.exists(state.resolve("aot/marker"))).isFalse();
        assertThat(Files.exists(jkBin)).isTrue();
        assertThat(Files.exists(foreign)).isTrue();
    }

    /**
     * The confirm table lists {@code cacheDir()} under "Path to Delete". After the nuke that path
     * is gone — no empty tier skeleton left behind by a wipe that recreated what it deleted, and
     * no survivors among the trees the tier table does not name.
     */
    @Test
    void cache_nuke_removes_the_cache_root_not_just_the_tiers_under_it() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(CacheTree.ACTIONS.under(cache));
        Files.writeString(CacheTree.ACTIONS.under(cache).resolve("marker"), "x");
        Files.createDirectories(CacheTree.HASH_MEMO.under(cache).resolve("aa"));
        Files.writeString(CacheTree.HASH_MEMO.under(cache).resolve("aa/memo"), "y");
        Files.createDirectories(cache.resolve("repos/central")); // not a tier — must not survive
        Files.writeString(cache.resolve("repos/central/lib.jar"), "z");
        Files.createDirectories(dirs.stateDir());

        // --cache --state takes the in-process wipe (the fleet is stopped first), which is the
        // leg default --all uses; the hosted purge is covered by CacheCommandTest.
        int exit = capture(() -> Jk.execute("self", "nuke", "--cache", "--state", "-y"));

        assertThat(exit).isZero();
        assertThat(cache).doesNotExist();
    }

    @Test
    void dry_run_cache_only_does_not_delete_or_show_self_table() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/dry-run-keep");
        Files.writeString(marker, "keep");

        // Single-target --cache uses the same path as `jk cache nuke` (no self plan table).
        String out = Capture.stdout(() -> assertThat(Jk.execute("self", "nuke", "--cache", "--dry-run", "-y"))
                .isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void config_under_jk_home_targets_config_dir_not_umbrella_home() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cfg");
        Path home = root.resolve("home");
        Path outsideBin = root.resolve("outside-bin");
        Files.createDirectories(home.resolve("data/lib"));
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(outsideBin);
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", outsideBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.CONFIG));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("config"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
    }

    @Test
    void guard_refuses_rows_that_contain_the_product_lib() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-anc");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("data/lib"));
        // JK_STATE_DIR mis-pointed at the umbrella root: state nuke must not take the whole tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STATE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STATE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("data/lib").startsWith(p));
    }

    @Test
    void guard_refuses_a_store_root_that_contains_the_product_lib() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-store");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("data/lib"));
        // JK_STORE_DIR mis-pointed at the umbrella root: the store row is delegated whole-tree to
        // the engine-side wipe, which deletes whatever root it is named — so the guards have to
        // refuse the row client-side.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STORE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("data/lib").startsWith(p));
    }

    @Test
    void a_refused_store_row_never_reaches_the_delegated_storage_nuke() throws Exception {
        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", isolatedHome.toString());
        try {
            Files.createDirectories(JkDirs.current().dataDir().resolve("versions"));
            var hosted = new SelfNukeCommand.Hosted() {
                boolean storageAsked;

                @Override
                public int storage() {
                    storageAsked = true;
                    return 0;
                }

                @Override
                public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
                    return 0;
                }
            };
            String err = captureText(() -> runNuke(hosted, true));
            assertThat(hosted.storageAsked)
                    .as("a store row the guards refused must not be handed to jk storage nuke")
                    .isFalse();
            assertThat(TestAnsi.strip(err)).contains("refusing to nuke the artifact store");
        } finally {
            if (prev == null) System.clearProperty("jk.env.JK_STORE_DIR");
            else System.setProperty("jk.env.JK_STORE_DIR", prev);
        }
    }

    @Test
    void a_refused_cache_row_never_reaches_the_delegated_cache_nuke() throws Exception {
        String prev = System.getProperty("jk.env.JK_CACHE_DIR");
        System.setProperty("jk.env.JK_CACHE_DIR", isolatedHome.toString());
        try {
            Files.createDirectories(JkDirs.current().dataDir().resolve("versions"));
            var hosted = new SelfNukeCommand.Hosted() {
                boolean cacheAsked;

                @Override
                public int storage() {
                    return 0;
                }

                @Override
                public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
                    cacheAsked = true;
                    return 0;
                }
            };
            String err = captureText(() -> runNuke(hosted, true));
            assertThat(hosted.cacheAsked)
                    .as("a cache row the guards refused must not be handed to jk cache nuke")
                    .isFalse();
            assertThat(TestAnsi.strip(err)).contains("refusing to nuke the cache tier");
        } finally {
            if (prev == null) System.clearProperty("jk.env.JK_CACHE_DIR");
            else System.setProperty("jk.env.JK_CACHE_DIR", prev);
        }
    }

    @Test
    void single_target_cache_refuses_a_guarded_cache_root() throws Exception {
        // The --cache shortcut bypasses the plan table, but not the guards: the shared nuke
        // deletes whatever root it is handed, so a JK_CACHE_DIR mis-pointed at the umbrella home
        // must be refused before it reaches the shared code path.
        String prev = System.getProperty("jk.env.JK_CACHE_DIR");
        System.setProperty("jk.env.JK_CACHE_DIR", isolatedHome.toString());
        try {
            Files.createDirectories(JkDirs.current().productLibDir());
            int exit = capture(() -> Jk.execute("self", "nuke", "--cache", "-y"));
            assertThat(exit).isNotZero();
            assertThat(JkDirs.current().productLibDir()).isDirectory();
        } finally {
            if (prev == null) System.clearProperty("jk.env.JK_CACHE_DIR");
            else System.setProperty("jk.env.JK_CACHE_DIR", prev);
        }
    }

    @Test
    void data_wipe_roots_is_store_plus_data_children_for_injected_dirs() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-seam");
        Path home = root.resolve("home");
        // JK_HOME mirrors XDG: store is $JK_HOME/data/store and the live engine $JK_HOME/data/lib.
        Files.createDirectories(home.resolve("data/store/lib"));
        Files.createDirectories(home.resolve("data/store/sha256"));
        Files.createDirectories(home.resolve("data/lib/jk-engine"));
        Files.createDirectories(home.resolve("data/completions"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        Path data = home.toAbsolutePath().normalize().resolve("data");
        assertThat(roots).containsExactlyInAnyOrder(data.resolve("store"), data.resolve("completions"));
        assertThat(roots).noneMatch(p -> p.equals(data.resolve("lib")));
    }

    @Test
    void symlinked_bin_dir_is_protected_via_realpath() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-link");
        Path home = root.resolve("home");
        Path realBin = home.resolve("data").resolve("bin"); // a bin dir living inside the data root
        Path linkBin = root.resolve("linked-bin");
        Files.createDirectories(realBin);
        Files.writeString(realBin.resolve("jk"), "#!/bin/sh\n");
        try {
            Files.createSymbolicLink(linkBin, realBin);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to verify here
        }
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", linkBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        Path realBinAbs = realBin.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(realBinAbs) || realBinAbs.startsWith(p));
    }

    @Test
    void data_sweeps_siblings_of_the_store_but_never_credentials_or_the_engine_jar() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cred");
        Path userHome = root.resolve("userhome");
        JkDirs dirs = JkDirs.of(env(), userHome.toString());
        Path data = dirs.dataDir();
        Files.createDirectories(data.resolve("credentials"));
        Files.createDirectories(data.resolve("repo-credentials"));
        Files.createDirectories(data.resolve("completions"));
        Files.createDirectories(data.resolve("lib").resolve("jk-engine"));
        Files.createDirectories(data.resolve("store").resolve("sha256"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.DATA));
        Path dataAbs = data.toAbsolutePath().normalize();
        assertThat(roots).containsExactlyInAnyOrder(dataAbs.resolve("store"), dataAbs.resolve("completions"));
        assertThat(roots).doesNotContain(dataAbs.resolve("credentials"));
        assertThat(roots).doesNotContain(dataAbs.resolve("repo-credentials"));
        assertThat(roots).doesNotContain(dataAbs.resolve("lib"));
    }

    private static Function<String, String> env(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map::get;
    }

    @Test
    void multi_target_settles_cache_then_self_with_blank_gaps() throws Exception {
        // Cache+state avoids the engine-hosted store wipe (needs suite JK_HOME). Storage→Cache→Self
        // ordering is covered by run() calling StorageCommand before CacheCommand; this asserts the
        // blank gaps between back-to-back settles and Cache before Self.
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Path state = dirs.stateDir();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/marker"), "cache");
        Files.createDirectories(state.resolve("aot"));
        Files.writeString(state.resolve("aot/marker"), "state");

        String out = Capture.stdout(() -> assertThat(Jk.execute("self", "nuke", "--cache", "--state", "-y"))
                .isZero());
        String plain = TestAnsi.strip(out);
        List<String> settles = Arrays.stream(plain.split("\n"))
                .filter(l -> l.contains("Nuked"))
                .toList();
        assertThat(settles).hasSizeGreaterThanOrEqualTo(2);
        assertThat(settles.get(0)).contains("Cache");
        assertThat(settles.get(1)).contains("Self");
        assertThat(plain).containsPattern("(?s)Cache[^\\n]*Nuked[^\\n]*\\n\\s*\\n[^\\n]*Self[^\\n]*Nuked");
        assertThat(cache.resolve("actions/marker")).doesNotExist();
        assertThat(state.resolve("aot/marker")).doesNotExist();
    }

    @Test
    void global_yes_works_before_the_command_and_between_group_and_sub() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/pre-yes"), "x");

        int exit = capture(() -> Jk.execute("-y", "self", "nuke", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/pre-yes")).doesNotExist();

        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/mid-yes"), "x");
        // Hidden alias: purge → nuke
        exit = capture(() -> Jk.execute("self", "--yes", "purge", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/mid-yes")).doesNotExist();
    }

    @Test
    void declining_the_prompt_deletes_nothing_and_exits_one() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/decline-keep");
        Files.writeString(marker, "keep");

        InputStream prevIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            int exit = capture(() -> Jk.execute("self", "nuke", "--cache"));
            assertThat(exit).isEqualTo(1);
        } finally {
            System.setIn(prevIn);
        }
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void dry_run_without_yes_does_not_prompt_and_exits_zero() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/dry-run-no-yes-keep");
        Files.writeString(marker, "keep");

        String out = Capture.stdout(() ->
                assertThat(Jk.execute("self", "nuke", "--cache", "--dry-run")).isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(TestAnsi.strip(out)).doesNotContain("Nuke aborted");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void displayPath_uses_tilde_under_home() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path under = home.resolve("cache").resolve("jk");
        assertThat(SelfNukeCommand.displayPath(under)).isEqualTo("~/cache/jk");
    }

    /** {@code self nuke --all} with the engine-hosted nukes stubbed; {@code apply} false = dry run. */
    private static int runNuke(SelfNukeCommand.Hosted hosted, boolean apply) {
        Invocation in = Invocation.builder()
                .flag("all", true)
                .flag("yes", apply)
                .flag("dry-run", !apply)
                .build();
        GlobalOptions.from(in); // installs assume-yes for Confirm
        try {
            return new SelfNukeCommand().run(in, hosted);
        } catch (Exception e) {
            throw new AssertionError("self nuke threw instead of reporting: " + e, e);
        }
    }

    /** As {@link #capture} but hands back what was printed. */
    private static String captureText(IntSupplier body) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        try {
            body.getAsInt();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static int capture(IntSupplier body) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        try {
            return body.getAsInt();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
