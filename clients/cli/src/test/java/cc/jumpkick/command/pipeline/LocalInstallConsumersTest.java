// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.runtime.AutoLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One project consumes another's {@code jk install} on the same machine with no repository: the
 * install lands in the Maven local repository or jk's {@code repos/jk-local} shelf, and a project
 * that pins the installed version by name resolves it from there. The lock says where it came from
 * ({@code source = "local-install"}), a re-install makes the consumer's lock stale by content, a
 * machine without the install says what to do, and a floating selector never lands on it.
 */
@Tag("integration")
class LocalInstallConsumersTest {

    private static final String GROUP = "zz.localinstall";

    /**
     * The installs these tests make, in the sandbox's Maven local repository and shelf — the ones
     * the engine reads (a client-side {@code jk.m2.local} does not reach it) — removed before and
     * after each test, so a run starts from a machine that never installed them.
     */
    @BeforeEach
    @AfterEach
    void removeInstalls() throws IOException {
        LockfileReader.clearCache();
        for (Path dir : List.of(
                m2Dir("liba-m2"),
                m2Dir("liba-shelf"),
                m2Dir("liba-float"),
                m2Dir("liba-new"),
                shelfDir("liba-shelf"))) {
            if (Files.exists(dir)) PathUtil.deleteRecursivelyOrThrow(dir);
        }
    }

    /**
     * {@code jk <verb> -C dir [--cache-dir dir/cache] args…}, its output echoed on failure; the exit
     * code and the output. {@code jk add} takes no cache directory.
     */
    private static Capture.Streams jk(int[] exit, Path dir, String verb, String... args) {
        boolean cache = !verb.equals("add");
        String[] all = new String[args.length + (cache ? 5 : 3)];
        all[0] = verb;
        all[1] = "-C";
        all[2] = dir.toString();
        if (cache) {
            all[3] = "--cache-dir";
            all[4] = dir.resolve("cache").toString();
        }
        System.arraycopy(args, 0, all, cache ? 5 : 3, args.length);
        Capture.Streams streams = Capture.both(() -> exit[0] = Jk.execute(all));
        if (exit[0] != 0) System.err.println("jk " + String.join(" ", all) + "\n" + streams.out() + streams.err());
        return streams;
    }

    /**
     * {@code jk <verb> -C dir args…} as a user types it, with no cache directory of the test's: a
     * project as {@code jk new} writes it is built where every build is.
     */
    private static Capture.Streams plain(int[] exit, Path dir, String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "-C";
        all[1] = dir.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        Capture.Streams streams = Capture.both(() -> exit[0] = Jk.execute(all));
        if (exit[0] != 0) System.err.println("jk " + String.join(" ", all) + "\n" + streams.out() + streams.err());
        return streams;
    }

    private static int jk(Path dir, String verb, String... args) {
        int[] exit = {0};
        jk(exit, dir, verb, args);
        return exit[0];
    }

    /** Project A: library {@code name} at 0.1.0 whose {@code hi()} returns {@code greeting}. */
    private static Path library(Path tmp, String name, String greeting) throws IOException {
        Path lib = tmp.resolve(name);
        Files.createDirectories(lib.resolve("src/main/java/zz/localinstall"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "%s"
                name    = "%s"
                version = "0.1.0"
                java    = 25
                """.formatted(GROUP, name));
        Files.writeString(
                lib.resolve("src/main/java/zz/localinstall/Lib.java"),
                "package zz.localinstall; public final class Lib { public static String hi() { return \"" + greeting
                        + "\"; } }\n");
        return lib;
    }

    /** Project B: an app pinning {@code GROUP:name} at {@code version}, calling {@code Lib.hi()}. */
    private static Path consumer(Path tmp, String name, String version) throws IOException {
        Path app = tmp.resolve("app-" + name);
        Files.createDirectories(app.resolve("src/main/java/zz/app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "zz.app"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { group = "%s", name = "%s", version = "%s" }
                """.formatted(GROUP, name, version));
        Files.writeString(
                app.resolve("src/main/java/zz/app/App.java"),
                "package zz.app; public final class App { public static String go() { return"
                        + " zz.localinstall.Lib.hi(); } }\n");
        return app;
    }

    private static Lockfile.Artifact row(Path app, String name) throws IOException {
        LockfileReader.clearCache();
        return LockfileReader.read(app.resolve("jk-lock.toml")).artifacts().stream()
                .filter(a -> a.name().startsWith(GROUP + ":" + name))
                .findFirst()
                .orElseThrow();
    }

    /** Where the Maven local repository keeps {@code GROUP:name}, every version. */
    private static Path m2Dir(String name) {
        return M2Dirs.localRepository().resolve(GROUP.replace('.', '/')).resolve(name);
    }

    /** Where jk's shelf keeps {@code GROUP:name}. */
    private static Path shelfDir(String name) {
        return JkStores.store()
                .resolve("repos")
                .resolve(RepositorySpec.JK_LOCAL)
                .resolve(GROUP.replace('.', '/'))
                .resolve(name);
    }

    private static Path m2Jar(String name) {
        return m2Dir(name).resolve("0.1.0-SNAPSHOT").resolve(name + "-0.1.0-SNAPSHOT.jar");
    }

    @Test
    void a_snapshot_installed_to_the_maven_local_repository_builds_another_project_and_follows_its_reinstall(
            @TempDir Path tmp) throws Exception {
        String name = "liba-m2";
        Path lib = library(tmp, name, "one");
        assertThat(jk(lib, "install", "-S")).isEqualTo(0);
        Path app = consumer(tmp, name, "0.1.0-SNAPSHOT");

        assertThat(jk(app, "build", "-S")).as("B builds with no [repositories]").isEqualTo(0);
        Lockfile.Artifact first = row(app, name);
        assertThat(first.source()).isEqualTo(RepositorySpec.LOCAL_INSTALL);
        assertThat(first.version()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(first.checksumHex()).isEqualTo(Hashing.sha256Hex(m2Jar(name)));
        assertThat(Files.readString(app.resolve("jk-lock.toml")))
                .doesNotContain(M2Dirs.localRepository().toString());
        Path lockFile = app.resolve("jk-lock.toml");
        assertThat(AutoLock.staleReason(app, lockFile)).isNull();

        // A is rebuilt and installed again: B's lock is stale by content, before and as the build sees it.
        library(tmp, name, "two");
        assertThat(jk(lib, "install", "-S")).isEqualTo(0);
        assertThat(AutoLock.staleReason(app, lockFile))
                .as("the forecast's reason, the build's freshen")
                .startsWith("jk-lock.toml is stale: the local install of " + GROUP + ":" + name
                        + ":0.1.0-SNAPSHOT changed (jk install at ");

        // jk explain updates the lock as the build does, and says what moved it.
        int[] exit = {0};
        Capture.Streams explained = jk(exit, app, "explain", "-S");
        assertThat(exit[0]).isZero();
        assertThat(explained.out())
                .contains("jk-lock.toml updated first, as jk build does: the local install of " + GROUP + ":" + name
                        + ":0.1.0-SNAPSHOT changed (jk install at ");

        assertThat(jk(app, "build", "-S")).isEqualTo(0);
        Lockfile.Artifact second = row(app, name);
        assertThat(second.checksumHex())
                .as("the build re-locked onto the re-installed jar")
                .isNotEqualTo(first.checksumHex())
                .isEqualTo(Hashing.sha256Hex(m2Jar(name)));
        assertThat(AutoLock.staleReason(app, lockFile)).isNull();

        // Another machine: the lock is committed, the install is not there. Offline, either the lock
        // is kept and the sync names the row, or the resolve finds the version nowhere and says it
        // is not installed; online, the update fails naming it (here the sandbox's denied hosts
        // answer for the remotes the update asks first).
        PathUtil.deleteRecursivelyOrThrow(m2Dir(name));
        Capture.Streams offline = jk(exit, app, "build", "-S", "--offline");
        assertThat(exit[0]).isNotZero();
        assertThat(offline.out() + offline.err())
                .contains(GROUP + ":" + name)
                .doesNotContain("listed but cannot be downloaded")
                .contains("run jk install in its project on this machine");
        Capture.Streams online = jk(exit, app, "build", "-S");
        assertThat(exit[0]).isNotZero();
        assertThat(online.out() + online.err())
                .contains(GROUP + ":" + name + ":0.1.0-SNAPSHOT came from a local install (jk install) that is no"
                        + " longer on this machine")
                .contains("run jk install in that project on this machine, or add a repository that serves it");
    }

    /**
     * The loop the feature is for, as {@code jk new} writes the consumer: its {@code jk-guards.toml}
     * extends the {@code library} pack, whose {@code no-snapshot-versions} rule refused the installed
     * snapshot, so a plain {@code jk build} (guards and tests on) failed where {@code -S} passed. A
     * developer's build and test take it, with one note saying what jk release and jk publish will
     * do; a CI build refuses it, saying to release or publish the library first.
     */
    @Test
    void a_project_as_jk_new_writes_it_builds_and_tests_on_an_installed_snapshot(@TempDir Path tmp) throws Exception {
        String name = "liba-new";
        String gav = GROUP + ":" + name + ":0.1.0-SNAPSHOT";
        assertThat(jk(library(tmp, name, "new"), "install")).isEqualTo(0);
        Path app = tmp.resolve("app-new");
        int[] exit = {0};
        Capture.Streams created = Capture.both(() -> exit[0] = Jk.execute("new", app.toString()));
        assertThat(exit[0]).as(created.out() + created.err()).isZero();
        assertThat(Files.readString(app.resolve("jk-guards.toml")))
                .as("the default template's guards extend the library pack")
                .contains("cc.jumpkick.guards:library:");
        assertThat(jk(app, "add", gav)).isEqualTo(0);
        assertThat(row(app, name).source()).isEqualTo(RepositorySpec.LOCAL_INSTALL);

        Capture.Streams build = plain(exit, app, "build");
        String said = build.out() + build.err();
        if (EnvValues.isCi(System::getenv)) {
            assertThat(exit[0]).as("under CI the pack's rule refuses it").isNotZero();
            assertThat(said)
                    .contains(GROUP + ":" + name + "@0.1.0-SNAPSHOT is a snapshot from a local install")
                    .contains("which a CI build refuses")
                    .contains("release or publish " + GROUP + ":" + name + " first");
            return;
        }
        assertThat(exit[0]).as("a plain jk build, guards and tests on").isZero();
        assertThat(said)
                .contains("depends on a local install of " + gav)
                .contains("jk release and jk publish refuse until it is a released version")
                .doesNotContain("is a snapshot");
        plain(exit, app, "test");
        assertThat(exit[0]).as("jk test").isZero();
    }

    @Test
    void a_snapshot_installed_to_jks_shelf_builds_another_project(@TempDir Path tmp) throws Exception {
        String name = "liba-shelf";
        Path shelf = shelfDir(name);
        Path lib = library(tmp, name, "shelved");
        assertThat(jk(lib, "install", "-S", "--destination", "jk")).isEqualTo(0);
        assertThat(shelf.resolve("0.1.0-SNAPSHOT")).isDirectory();
        assertThat(m2Jar(name)).doesNotExist();
        Path app = consumer(tmp, name, "0.1.0-SNAPSHOT");

        assertThat(jk(app, "build", "-S")).isEqualTo(0);
        Lockfile.Artifact row = row(app, name);
        assertThat(row.source()).isEqualTo(RepositorySpec.LOCAL_INSTALL);
        assertThat(row.checksumHex())
                .isEqualTo(Hashing.sha256Hex(shelf.resolve("0.1.0-SNAPSHOT/" + name + "-0.1.0-SNAPSHOT.jar")));
    }

    /**
     * {@code latest} asks for the newest release a repository publishes; what this machine
     * installed is not one. Offline, so the answer is the installs' alone: the lock finds nothing,
     * and {@code jk add} with no version finds no release to pin.
     */
    @Test
    void a_floating_selector_never_lands_on_an_installed_snapshot(@TempDir Path tmp) throws Exception {
        String name = "liba-float";
        assertThat(jk(library(tmp, name, "float"), "install", "-S")).isEqualTo(0);
        assertThat(m2Jar(name)).exists();
        Path app = consumer(tmp, name, "latest");

        int[] exit = {0};
        Capture.Streams lock = jk(exit, app, "lock", "--offline");

        assertThat(exit[0])
                .as("nothing but the install has %s, and latest does not mean it", name)
                .isNotZero();
        assertThat(lock.out() + lock.err()).contains(GROUP + ":" + name);
        if (Files.exists(app.resolve("jk-lock.toml"))) {
            assertThat(Files.readString(app.resolve("jk-lock.toml"))).doesNotContain(name);
        }
        Path blank = tmp.resolve("app-add");
        Files.createDirectories(blank);
        String manifest = "group = \"zz.app\"\nname = \"blank\"\nversion = \"1.0.0\"\njava = 25\n";
        Files.writeString(blank.resolve("jk.toml"), manifest);
        assertThat(jk(blank, "add", "--offline", GROUP + ":" + name)).isNotZero();
        assertThat(Files.readString(blank.resolve("jk.toml"))).isEqualTo(manifest);
    }
}
