// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.SelfNukeCommand.Target;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs against a per-test {@code JK_HOME}/{@code JK_STATE_DIR} overlay ({@code jk.env.*} seam,
 * same as {@link cc.jumpkick.cli.engine.IsolatedStoreExtension}) — NOT the suite-shared home.
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

    @Test
    void wipeRoots_never_includes_bin_jdks_product_lib_or_store_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        Path productLib = dirs.productLibDir().toAbsolutePath().normalize();
        Path lib = dirs.libDir().toAbsolutePath().normalize();
        Files.createDirectories(productLib);
        Files.createDirectories(lib.resolve("jk-java-compiler"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs);
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            assertThat(abs).isNotEqualTo(bin);
            assertThat(abs).isNotEqualTo(jdks);
            assertThat(abs).isNotEqualTo(productLib);
            assertThat(abs).isNotEqualTo(lib);
            assertThat(abs.startsWith(bin)).isFalse();
            assertThat(abs.startsWith(jdks)).isFalse();
            assertThat(abs.startsWith(productLib)).isFalse();
            assertThat(abs.startsWith(lib)).isFalse();
        }
    }

    @Test
    void store_target_is_the_store_root_only_same_as_storage_nuke() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir().toAbsolutePath().normalize();
        Files.createDirectories(store.resolve("sha256"));
        Files.createDirectories(dirs.libDir());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        assertThat(roots).containsExactly(store);
    }

    @Test
    @Tag("integration")
    void store_nuke_wipes_cas_including_lib_keeps_engine_jar_and_bin() throws Exception {
        JkDirs dirs = JkDirs.current();
        // The isolated home carries a REAL materialized engine (isolateHome copy) — the hosted
        // nuke needs it to run, and its survival is exactly what this test asserts.
        Path engineHome = dirs.productLibDir().resolve("jk-engine");
        Path cas = dirs.storeDir().resolve("sha256");
        Path lib = dirs.libDir().resolve("jk-java-compiler");
        Path bin = dirs.binDirectory();
        Files.createDirectories(engineHome);
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("plugin.jar"), "plugin");
        Files.createDirectories(bin);
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        int exit = capture(() -> Jk.execute("self", "nuke", "--store", "-y"));
        assertThat(exit).isZero();
        // storage nuke: entire store, including plugin lib
        assertThat(cas.resolve("ab/blob")).doesNotExist();
        assertThat(lib.resolve("plugin.jar")).doesNotExist();
        // product-lib engine + PATH are not part of the store
        assertThat(engineHome).isDirectory();
        assertThat(foreign).exists();
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
        Files.createDirectories(home.resolve("lib"));
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
    void guard_refuses_rows_that_contain_product_lib_or_store_lib() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-anc");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("store").resolve("lib"));
        // JK_STATE_DIR mis-pointed at the umbrella root: state nuke must not take the whole tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STATE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STATE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("lib").startsWith(p));
    }

    @Test
    void store_wipe_roots_is_store_dir_for_injected_dirs() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-seam");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("store").resolve("lib"));
        Files.createDirectories(home.resolve("store").resolve("sha256"));
        Files.createDirectories(home.resolve("lib"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("store"));
    }

    @Test
    void symlinked_bin_dir_is_protected_via_realpath() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-link");
        Path home = root.resolve("home");
        Path realBin = home.resolve("data").resolve("bin");
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

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path realBinAbs = realBin.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(realBinAbs) || realBinAbs.startsWith(p));
    }

    @Test
    void store_does_not_schedule_credentials_or_data_siblings() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cred");
        Path userHome = root.resolve("userhome");
        JkDirs dirs = JkDirs.of(env(), userHome.toString());
        Path data = dirs.dataDir();
        Files.createDirectories(data.resolve("credentials"));
        Files.createDirectories(data.resolve("repo-credentials"));
        Files.createDirectories(data.resolve("completions"));
        Files.createDirectories(data.resolve("store").resolve("sha256"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path dataAbs = data.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(dataAbs.resolve("store"));
        assertThat(roots).doesNotContain(dataAbs.resolve("credentials"));
        assertThat(roots).doesNotContain(dataAbs.resolve("completions"));
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
