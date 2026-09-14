// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.command.system.SelfNukeCommand.Target;
import cc.jumpkick.command.toolchain.ToolListCommand;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            Path fromLib = Path.of(suiteHome).resolve("lib").resolve("jk-engine");
            Path toLib = Files.createDirectories(isolatedHome.resolve("lib").resolve("jk-engine"));
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
        // The store and cache nukes run engine-side; state and config do not. An engine that
        // cannot start used to unwind the whole command, so a user who had just approved a table
        // of paths got an error about the engine and every path still there.
        JkDirs dirs = JkDirs.current();
        Path config = dirs.userConfigFilePath();
        Path perApp = dirs.configDir().resolve("demo");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "x = 1");
        Files.createDirectories(perApp);
        Files.writeString(perApp.resolve("config.toml"), "v");

        int exit = capture(() -> runNuke(new BrokenEngine(), true));

        assertThat(exit).as("a partial nuke must not report success").isNotZero();
        assertThat(config).as("config needs no engine to delete").doesNotExist();
        assertThat(perApp).as("per-app config needs no engine to delete").doesNotExist();
    }

    /** A store nuke whose engine writes a subtree back before the fleet is stopped. */
    private static final class WritesBackAfterWipe implements SelfNukeCommand.Hosted {
        private final Path store;

        WritesBackAfterWipe(Path store) {
            this.store = store;
        }

        @Override
        public int storage() throws IOException {
            PathUtil.deleteRecursivelyOrThrow(store);
            Path shard = Files.createDirectories(store.resolve("sha256/zz"));
            Files.writeString(shard.resolve("blob"), "an on-demand writer's put");
            return 0;
        }

        @Override
        public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
            return 0;
        }
    }

    @Test
    void a_store_the_engine_wrote_back_between_its_wipe_and_its_stop_is_gone_when_the_command_returns()
            throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cas = Files.createDirectories(dirs.storeDir().resolve("sha256/ab"));
        Files.writeString(cas.resolve("blob"), "cas");

        int exit = capture(() -> runNuke(new WritesBackAfterWipe(dirs.storeDir()), EnumSet.of(Target.STORE), true));

        assertThat(exit).isZero();
        assertThat(dirs.storeDir()).as("the row the table promised gone").doesNotExist();
    }

    @Test
    void an_unreachable_engine_names_what_it_could_not_remove() throws Exception {
        // Silence here is the actual harm: the paths that survive are exactly the ones the user
        // cannot see, so the command has to say which targets it left behind.
        Files.createDirectories(JkDirs.current().homeDir());
        Files.writeString(JkDirs.current().userConfigFilePath(), "x = 1");

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
        Path config = JkDirs.current().userConfigFilePath();
        Files.createDirectories(config.getParent());
        Files.writeString(config, "x = 1");
        long before = countFiles(isolatedHome);

        var hosted = new SelfNukeCommand.Hosted() {
            boolean storageAsked;

            @Nullable
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
    private static final Set<String> KEPT_ROOTS = Set.of(
            "binDirectory",
            "binDir",
            "jdksDir",
            "jdks",
            "productLibDir",
            "productLib",
            "credsDir",
            "creds",
            // The umbrella itself is never a row: --all empties the roots inside it and leaves
            // bin, lib and creds standing, so scheduling the home would delete all three.
            "homeDir",
            "home");

    /**
     * Every directory jk owns is either nuked or deliberately kept — and adding a new one to
     * {@link JkDirs} without deciding which fails here.
     *
     * <p>Within a root the nuke is already exhaustive: each root is removed whole-tree (the store
     * engine-side), so everything beneath it goes with it. What was *not* whitelist-shaped is the
     * set of roots itself — a hand-maintained list that a new accessor would silently fall out of,
     * leaving files behind that a user who ran {@code --all} believes are gone.
     */
    @Test
    void every_JkDirs_root_is_either_nuked_or_deliberately_kept() throws Exception {
        JkDirs dirs = JkDirs.current();
        Files.createDirectories(dirs.configDir());
        List<Path> planned = SelfNukeCommand.plan(dirs, EnumSet.allOf(Target.class)).stream()
                .map(SelfNukeCommand.PurgeRow::path)
                .toList();

        List<String> unaccounted = new ArrayList<>();
        for (Method m : JkDirs.class.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != Path.class) continue;
            String name = m.getName();
            if (KEPT_ROOTS.contains(name)) continue;
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
                        + " KEPT_ROOTS here with the reason it survives.")
                .isEmpty();
    }

    @Test
    void the_kept_roots_are_actually_kept() throws Exception {
        JkDirs dirs = JkDirs.current();
        Files.createDirectories(dirs.credsDir());
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
    void wipeRoots_never_includes_bin_jdks_creds_or_the_product_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        Path productLib = dirs.productLibDir().toAbsolutePath().normalize();
        Path creds = dirs.credsDir().toAbsolutePath().normalize();
        Files.createDirectories(productLib);
        Files.createDirectories(creds);

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs);
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            for (Path guarded : List.of(bin, jdks, productLib, creds)) {
                assertThat(abs).isNotEqualTo(guarded);
                assertThat(abs.startsWith(guarded)).isFalse();
            }
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

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));

        assertThat(roots).anyMatch(storeLib::startsWith);
    }

    /**
     * One row, the root itself — completions, android-sdk and the CAS are all inside it now, so
     * there is nothing left to enumerate child-by-child.
     */
    @Test
    void store_target_is_exactly_the_store_root() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir().toAbsolutePath().normalize();
        Files.createDirectories(store.resolve("sha256"));
        Files.createDirectories(store.resolve("android-sdk"));
        Files.createDirectories(store.resolve("completions"));

        assertThat(SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE))).containsExactly(store);
    }

    @Test
    @Tag("integration")
    void store_nuke_wipes_the_store_and_keeps_engine_credentials_and_bin() throws Exception {
        JkDirs dirs = JkDirs.current();
        // The isolated home carries a REAL materialized engine (isolateHome copy) — the hosted
        // nuke needs it to run, and its survival is exactly what this test asserts.
        Path engineHome = dirs.productLibDir().resolve("jk-engine");
        Path cas = dirs.storeDir().resolve("sha256");
        Path lib = dirs.storeDir().resolve("lib").resolve("jk-java-compiler");
        Path bin = dirs.binDirectory();
        Path creds = dirs.credsDir().resolve("forge");
        Path repoCreds = dirs.credsDir().resolve("repo");
        Path completions = dirs.storeDir().resolve("completions");
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

        int exit = capture(() -> Jk.execute("self", "nuke", "--store", "-y"));
        assertThat(exit).isZero();
        // storage nuke: entire store, including plugin lib
        assertThat(cas.resolve("ab/blob")).doesNotExist();
        assertThat(lib.resolve("plugin.jar")).doesNotExist();
        assertThat(repoJar)
                .as("the Maven-layout tree goes too — this is what a worker holds open")
                .doesNotExist();
        // completions live under the store now, so they go with it
        assertThat(completions).doesNotExist();
        // product-lib engine, credentials, and PATH survive — each a root of its own
        assertThat(engineHome).isDirectory();
        assertThat(creds.resolve("github.json")).exists();
        assertThat(repoCreds.resolve("central.json")).exists();
        assertThat(foreign).exists();
    }

    /**
     * Every row of the confirm table, not one of them. The "Path to Delete" column may only list
     * paths that are gone <em>as directories</em> afterwards; made that true for the cache
     * and state roots and left the artifact store emptied-but-standing, which is the same table
     * saying "delete" and meaning "empty". {@code wipeRoots} is the row set the table prints.
     */
    @Test
    @Tag("integration")
    void store_nuke_removes_every_path_the_confirm_table_listed() throws Exception {
        JkDirs dirs = JkDirs.current();
        Files.createDirectories(dirs.productLibDir().resolve("jk-engine"));
        Path cas = dirs.storeDir().resolve("sha256/ab");
        Files.createDirectories(cas);
        Files.writeString(cas.resolve("blob"), "cas");
        Path tools = dirs.storeDir().resolve("lib").resolve("jk-java-compiler"); // <store>/lib — a child of the store
        Files.createDirectories(tools);
        Files.writeString(tools.resolve("plugin.jar"), "plugin");
        Files.createDirectories(dirs.storeDir().resolve("completions"));
        Files.writeString(dirs.storeDir().resolve("completions/zsh"), "#compdef jk");
        Files.createDirectories(dirs.storeDir().resolve("android-sdk"));
        Path creds = dirs.credsDir().resolve("forge");
        Files.createDirectories(creds);
        Files.writeString(creds.resolve("github.json"), "{}");
        Path repoCreds = dirs.credsDir().resolve("repo");
        Files.createDirectories(repoCreds);
        Files.writeString(repoCreds.resolve("central.json"), "{}");
        Files.createDirectories(dirs.binDirectory());
        Path foreign = dirs.binDirectory().resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        List<Path> tableRows = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        assertThat(tableRows).contains(dirs.storeDir().toAbsolutePath().normalize());

        assertThat(capture(() -> Jk.execute("self", "nuke", "--store", "-y"))).isZero();

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
    void store_and_state_nuke_leaves_no_engine_to_write_the_state_dir_back() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cas = dirs.storeDir().resolve("sha256");
        Files.createDirectories(dirs.productLibDir().resolve("jk-engine"));
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(dirs.stateDir().resolve("aot"));
        Files.writeString(dirs.stateDir().resolve("aot/marker"), "aot");

        int exit = capture(() -> Jk.execute("self", "nuke", "--store", "--state", "-y"));

        assertThat(exit).isZero();
        assertThat(EngineFleet.listThisHome()).isEmpty();
        assertThat(dirs.stateDir()).doesNotExist();
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

    /** Config is two paths under the home root, and the home root is not one of them. */
    @Test
    void config_targets_the_config_file_and_the_per_app_tree_never_the_home_root(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("config"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.CONFIG));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactlyInAnyOrder(homeAbs.resolve("config.toml"), homeAbs.resolve("config"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
    }

    @Test
    void guard_refuses_rows_that_contain_the_product_lib(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("lib"));
        // JK_STATE_DIR mis-pointed at the home root: state nuke must not take the whole tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STATE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STATE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("lib").startsWith(p));
    }

    @Test
    void guard_refuses_a_store_root_that_contains_the_product_lib(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("lib"));
        // JK_STORE_DIR mis-pointed at the home root: the store row is delegated whole-tree to the
        // engine-side wipe, which deletes whatever root it is named — so the guards have to refuse
        // the row client-side.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STORE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("lib").startsWith(p));
    }

    @Test
    void a_refused_store_row_never_reaches_the_delegated_storage_nuke() throws Exception {
        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", isolatedHome.toString());
        try {
            Files.createDirectories(JkDirs.current().configDir().resolve("demo"));
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
            Files.createDirectories(JkDirs.current().configDir().resolve("demo"));
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
    void store_wipe_root_is_exactly_the_store_for_injected_dirs(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("store/repos"));
        Files.createDirectories(home.resolve("store/completions"));
        Files.createDirectories(home.resolve("lib/jk-engine"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("store"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs.resolve("lib")));
    }

    /**
     * A root a user pointed at another volume is reached through a symlink, and the guards compare
     * real paths for exactly that case: {@code JK_STORE_DIR} aimed at a link whose target is the
     * product lib must be refused, not followed.
     */
    @Test
    void a_symlinked_root_that_resolves_onto_a_guarded_tree_is_refused(@TempDir Path root) throws Exception {
        Path home = root.resolve("home");
        Path realLib = home.resolve("lib");
        Path link = root.resolve("linked-store");
        Files.createDirectories(realLib.resolve("jk-engine"));
        try {
            Files.createSymbolicLink(link, realLib);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to verify here
        }
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STORE_DIR", link.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path realLibAbs = realLib.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(realLibAbs) || realLibAbs.startsWith(p));
    }

    /**
     * Structure, not a carve-out: {@code creds} and {@code lib} are siblings of {@code store}, so
     * a whole-tree store wipe cannot reach either. This is the assertion that would have to be
     * deleted before {@code --store} could log a user out.
     */
    @Test
    void store_nuke_can_never_reach_creds_or_the_engine_jar(@TempDir Path root) throws Exception {
        JkDirs dirs = JkDirs.of(env(), root.resolve("userhome").toString());
        Files.createDirectories(dirs.credsDir().resolve("forge"));
        Files.createDirectories(dirs.credsDir().resolve("repo"));
        Files.createDirectories(dirs.productLibDir().resolve("jk-engine"));
        Files.createDirectories(dirs.storeDir().resolve("completions"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.allOf(Target.class));
        Path creds = dirs.credsDir().toAbsolutePath().normalize();
        Path lib = dirs.productLibDir().toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> creds.equals(p) || creds.startsWith(p));
        assertThat(roots).noneMatch(p -> lib.equals(p) || lib.startsWith(p));
    }

    private static Function<String, @Nullable String> env(String... kv) {
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

    /**
     * A launcher in bin execs the absolute classpath its env under {@code <state>/tools/envs}
     * records. Deleting the state root and leaving the launcher gives a bin full of scripts that
     * fail with "could not find or load main class" under a settle line saying tools were kept.
     */
    @Test
    void state_nuke_removes_the_launchers_of_the_tool_envs_it_deletes() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = Files.createDirectories(dirs.binDirectory());
        Path launcher = installedTool(dirs, "widget");
        Path winLauncher = bin.resolve("widget.cmd");
        Files.writeString(winLauncher, "@echo off\r\n");
        installedTool(dirs, "alpha");
        // An env directory under a name jk reserves for its own files never names a launcher row.
        Files.createDirectories(dirs.toolEnvsDir().resolve("jk"));
        Path product = bin.resolve("jk");
        Files.writeString(product, "the client");
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        String out = TestAnsi.strip(captureText(() -> runNuke(new BrokenEngine(), EnumSet.of(Target.STATE), true)));

        assertThat(dirs.stateDir()).doesNotExist();
        assertThat(launcher).doesNotExist();
        assertThat(winLauncher).doesNotExist();
        assertThat(bin.resolve("alpha")).doesNotExist();
        assertThat(product).as("jk's own client stays").exists();
        assertThat(foreign).as("a file jk did not install stays").exists();
        assertThat(out).contains("the tool launchers and envs listed above go with the roots they run from");
        String settle = out.lines()
                .filter(l -> l.contains("Nuked selected"))
                .findFirst()
                .orElse("");
        assertThat(settle)
                .contains("3 installed tool launchers")
                .as("the settle line does not claim what it just orphaned survives")
                .doesNotContain("installed app jars");
    }

    @Test
    void a_dry_run_lists_the_tool_launchers_and_leaves_them() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path launcher = installedTool(dirs, "widget");

        String out = TestAnsi.strip(captureText(() -> runNuke(new BrokenEngine(), EnumSet.of(Target.STATE), false)));

        assertThat(launcher).exists();
        assertThat(dirs.toolEnvsDir().resolve("widget/env.json")).exists();
        assertThat(out).contains("would remove " + SelfNukeCommand.displayPath(launcher));
    }

    /**
     * An installed tool's launcher execs jars under {@code <store>/sha256/…}. Wiping the store and
     * leaving the launcher gives the same dead script a state wipe would — "could not find or
     * load main class" — and leaving the env keeps the tool in {@code jk tool list} with nothing
     * behind it. So the store target schedules exactly the tools whose recorded classpath lies
     * under the store: their launchers and their envs, listed and counted like the state target's.
     */
    @Test
    void store_nuke_removes_the_envs_and_launchers_of_the_tools_whose_classpaths_it_deletes() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir();
        Path casJar = Files.createDirectories(store.resolve("sha256/ab")).resolve("widget.jar");
        Files.writeString(casJar, "jar");
        Path widget = installedTool(dirs, "widget", casJar);
        Path widgetCmd = Files.writeString(dirs.binDirectory().resolve("widget.cmd"), "@echo off\r\n");
        Path elsewhere = Files.writeString(isolatedHome.resolve("elsewhere.jar"), "jar");
        Path local = installedTool(dirs, "local", elsewhere);
        var wipesStore = new SelfNukeCommand.Hosted() {
            @Override
            public int storage() throws IOException {
                PathUtil.deleteRecursivelyOrThrow(store);
                return 0;
            }

            @Override
            public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
                throw new AssertionError("the cache target is not selected");
            }
        };

        String out = TestAnsi.strip(captureText(() -> runNuke(wipesStore, EnumSet.of(Target.STORE), true)));

        assertThat(store).doesNotExist();
        assertThat(widget).doesNotExist();
        assertThat(widgetCmd).doesNotExist();
        assertThat(dirs.toolEnvsDir().resolve("widget"))
                .as("the env whose classpath is gone goes with it")
                .doesNotExist();
        assertThat(local).as("a launcher the store wipe leaves runnable stays").exists();
        assertThat(dirs.toolEnvsDir().resolve("local/env.json")).exists();
        String listed = TestAnsi.strip(captureText(() -> toolList(dirs)));
        assertThat(listed)
                .as("jk tool list names only tools that can run")
                .contains("local")
                .doesNotContain("widget");
        try (var launchers = Files.list(dirs.binDirectory())) {
            for (Path launcher : launchers.toList()) {
                assertThat(Files.readString(launcher))
                        .as("%s points under the deleted store", launcher)
                        .doesNotContain(store.toString());
            }
        }
        assertThat(out)
                .contains("Launcher of tool widget")
                .contains("Env of tool widget")
                .doesNotContain("Launcher of tool local")
                .doesNotContain("Env of tool local");
        String settle = out.lines()
                .filter(l -> l.contains("Nuked selected"))
                .findFirst()
                .orElse("");
        // widget's POSIX launcher and its .cmd twin — two files — plus its env, and nothing for local.
        assertThat(settle).contains("2 installed tool launchers and 1 orphaned tool env (jk install restores them)");
    }

    @Test
    void tool_rows_exist_only_when_a_root_the_tool_runs_from_goes(@TempDir Path root) throws Exception {
        JkDirs dirs = JkDirs.current();
        Path casJar = dirs.storeDir().resolve("sha256/ab/widget.jar");
        installedTool(dirs, "widget", casJar);
        installedTool(dirs, "local", isolatedHome.resolve("elsewhere.jar"));
        Path widget = dirs.binDirectory().resolve("widget").toAbsolutePath().normalize();
        Path widgetEnv = dirs.toolEnvsDir().resolve("widget").toAbsolutePath().normalize();
        Path local = dirs.binDirectory().resolve("local").toAbsolutePath().normalize();
        // The state root holds every env, so every launcher goes with it — and the state row
        // already takes the envs, so no env row doubles it.
        List<SelfNukeCommand.PurgeRow> stateRows = SelfNukeCommand.plan(dirs, EnumSet.of(Target.STATE));
        assertThat(SelfNukeCommand.toolOrphanRows(dirs, stateRows))
                .extracting(SelfNukeCommand.PurgeRow::path)
                .containsExactly(local, widget);
        // The store holds only widget's classpath: its launcher and, since no scheduled root
        // holds it, its env.
        List<SelfNukeCommand.PurgeRow> storeRows = SelfNukeCommand.plan(dirs, EnumSet.of(Target.STORE));
        assertThat(SelfNukeCommand.toolOrphanRows(dirs, storeRows))
                .extracting(SelfNukeCommand.PurgeRow::path, SelfNukeCommand.PurgeRow::kind)
                .containsExactly(
                        tuple(widget, SelfNukeCommand.Kind.LAUNCHER), tuple(widgetEnv, SelfNukeCommand.Kind.ENV));
        // Cache or config alone deletes no env and no classpath, so it orphans no tool.
        List<SelfNukeCommand.PurgeRow> configRows = SelfNukeCommand.plan(dirs, EnumSet.of(Target.CONFIG));
        assertThat(SelfNukeCommand.toolOrphanRows(dirs, configRows)).isEmpty();
        // A refused state root (one that would reach the product lib) deletes nothing under it.
        Map<String, String> env = new HashMap<>();
        env.put("JK_HOME", root.toString());
        env.put("JK_STATE_DIR", root.toString());
        JkDirs refused = JkDirs.of(env::get, root.toString());
        assertThat(SelfNukeCommand.toolOrphanRows(refused, SelfNukeCommand.plan(refused, EnumSet.of(Target.STATE))))
                .isEmpty();
    }

    /** {@code jk tool list} against this home's state and bin. */
    private static int toolList(JkDirs dirs) {
        Invocation in = Invocation.builder()
                .putValue("state-dir", dirs.stateDir().toString())
                .putValue("bin-dir", dirs.binDirectory().toString())
                .build();
        try {
            return new ToolListCommand().run(in);
        } catch (IOException e) {
            throw new AssertionError("tool list threw: " + e, e);
        }
    }

    /** {@code jk install <name>}'s footprint: the env under state and a launcher in bin. */
    private static Path installedTool(JkDirs dirs, String name) throws IOException {
        return installedTool(dirs, name, Path.of("/store/gone.jar"));
    }

    /** As above, with the env recording {@code classpath} as the one jar the launcher execs. */
    private static Path installedTool(JkDirs dirs, String name, Path classpath) throws IOException {
        Path env = Files.createDirectories(dirs.toolEnvsDir().resolve(name));
        String jar = classpath.toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(env.resolve("env.json"), "{\"binName\": \"" + name + "\", \"classpath\": [\"" + jar + "\"]}");
        Path launcher = Files.createDirectories(dirs.binDirectory()).resolve(name);
        Files.writeString(launcher, "#!/usr/bin/env bash\nexec java -cp " + jar + " Main \"$@\"\n");
        return launcher;
    }

    /** {@code self nuke --all} with the engine-hosted nukes stubbed; {@code apply} false = dry run. */
    private static int runNuke(SelfNukeCommand.Hosted hosted, boolean apply) {
        return runNuke(hosted, EnumSet.allOf(Target.class), apply);
    }

    /** {@code self nuke} with exactly {@code targets} named on the command line. */
    private static int runNuke(SelfNukeCommand.Hosted hosted, Set<Target> targets, boolean apply) {
        Invocation.Builder b = Invocation.builder().flag("yes", apply).flag("dry-run", !apply);
        if (targets.equals(EnumSet.allOf(Target.class))) {
            b.flag("all", true);
        } else {
            for (Target t : targets) b.flag(t.name().toLowerCase(Locale.ROOT), true);
        }
        Invocation in = b.build();
        // Dispatch installs assume-yes around a leaf command; this drives the command body directly.
        Confirm.setAssumeYes(apply);
        try {
            return new SelfNukeCommand().run(in, hosted);
        } catch (Exception e) {
            throw new AssertionError("self nuke threw instead of reporting: " + e, e);
        } finally {
            Confirm.clearAssumeYes();
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
