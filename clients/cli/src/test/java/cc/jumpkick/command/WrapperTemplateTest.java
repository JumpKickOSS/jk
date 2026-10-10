// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.toolchain.WrapperCommand;
import cc.jumpkick.host.Os;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.testing.Sleepers;
import cc.jumpkick.testing.Symlinks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wrapper scripts are bootstrappers, not pins: they may depend on exactly two surfaces —
 * the release URL layout (releases.md, including {@code SHA256SUMS}) and the lock's optional
 * one-line {@code jk-min} floor — and nothing else about jk. No version pin, no artifact sha
 * from the lock, no daemon awareness.
 */
class WrapperTemplateTest {

    private static String template(String name) throws Exception {
        try (InputStream in = WrapperCommand.class.getResourceAsStream("wrapper/" + name)) {
            assertThat(in).as("template " + name + " bundled in the jar").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void posix_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String sh = template("jk.sh");
        // The two frozen dependencies: the release layout and the lock's optional floor.
        assertThat(sh).contains("latest/LATEST").contains("SHA256SUMS.sig").doesNotContain("latest/LATEST.sig");
        // The pointer is signed data, verified before the version it names is used for anything.
        assertThat(sh.indexOf("verify_release_signature \"$TMP/LATEST.body\""))
                .isLessThan(sh.indexOf("jk-$TARGET-$VERSION.xz"));
        assertThat(sh).contains("/^version [0-9]+").contains("/^issued [0-9]+$/");
        assertThat(sh).doesNotContain("latest/VERSION");
        assertThat(sh).contains(ReleaseVerifier.BUILT_IN_KEY);
        assertThat(sh).contains("\"jk-min = \"*").contains("$SEARCH/jk-lock.toml");
        // Bin resolution mirrors install.sh/JkDirs: one home, one bin, no cascade to drift from.
        assertThat(sh).contains("$BIN_DIR/jk").contains("HOME_DIR=\"${JK_HOME:-$HOME/.jk}\"");
        assertThat(sh).doesNotContain("XDG_").doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Downloads authenticate the manifest, then verify its exact artifact entry. The pointer and
        // the manifest go through the one signature helper: a single openssl verify in the file.
        assertThat(sh).contains("verify_release_signature \"$TMP/SHA256SUMS\" \"$TMP/SHA256SUMS.sig\"");
        assertThat(occurrences(sh, "openssl dgst -sha256 -verify")).isEqualTo(1);
        assertThat(sh).contains("matches != 1");
        assertThat(sh).doesNotContain("\"jk = \"*").doesNotContain("sha256 = ");
        // Newest installed wins when it satisfies the floor; a stale channel is a hard error.
        assertThat(sh).contains("ver_ge").contains("requires jk >=");
        // The wrapper fetches the .xz that self-update inflates. install.sh fetches .gz.
        assertThat(sh).contains("jk-$TARGET-$VERSION.xz").contains("xz -dc");
        // The signed sums, read before any client is fetched, decide native or JVM: the native
        // client when they list one for this host, else the JVM client, which writes its launcher.
        assertThat(sh.indexOf("verify_release_signature \"$TMP/SHA256SUMS\""))
                .isLessThan(sh.indexOf("jk-$TARGET-$VERSION.xz"))
                .isLessThan(sh.indexOf("fetch \"$FILE\""));
        assertThat(sh).contains("FILE=\"jk-$VERSION.jar\"").contains("self write-launcher");
        assertThat(sh).contains("CLIENT=\"${JK_CLIENT:-}\"").contains("unset JK_CLIENT to use the JVM client");
        // The same refusal JkDirs makes: a relative JK_HOME is not a home jk would read.
        assertThat(sh).contains("JK_HOME must be an absolute path");
        // A jk found on PATH is exec'd only when it is not this wrapper by identity or by content.
        assertThat(sh).contains("real_path \"$PATH_JK\"").contains("is_wrapper \"$PATH_JK\"");
        // A failed or empty VERSION fetch is an error that names the URL, never a bare download.
        assertThat(sh).contains("could not read $RELEASES/latest/LATEST").contains("is not a release pointer");
        // A release file the host does not serve is refused by name, not with curl's bare status.
        assertThat(sh).contains("could not read $RELEASES/$VERSION/$1");
        assertThat(sh).doesNotContain(".zip");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness.
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("'/latest/'").contains("'LATEST'").contains("SHA256SUMS.sig");
        assertThat(bat).doesNotContain("'LATEST.sig'");
        assertThat(bat).contains("^version ([0-9]+").contains("if not defined VERSION");
        assertThat(bat).doesNotContain("latest/VERSION");
        assertThat(bat).contains("RSASignaturePadding]::Pkcs1").contains("$count -ne 1");
        // One :verify_signature subroutine checks both remote inputs, each before it is read.
        assertThat(occurrences(bat, "VerifyData(")).isEqualTo(1);
        assertThat(bat.indexOf("call :verify_signature JK_WRAPPER_PTMP LATEST.body"))
                .isGreaterThan(bat.indexOf("'/latest/'"))
                .isLessThan(bat.indexOf("Write-Output $Matches[1]"));
        assertThat(bat.indexOf("call :verify_signature JK_WRAPPER_TMP SHA256SUMS"))
                .isGreaterThan(0)
                .isLessThan(bat.indexOf("Get-FileHash"));
        assertThat(bat).contains(ReleaseVerifier.BUILT_IN_RSA_MODULUS).contains(ReleaseVerifier.BUILT_IN_RSA_EXPONENT);
        // The floor is the one exact `jk-min = ` line of the nearest lock at or above the script.
        assertThat(bat).contains("findstr /b /c:\"jk-min = \" \"!LOCK_DIR!jk-lock.toml\"");
        assertThat(bat).contains(":find_lock").contains("if !LOCK_DEPTH! lss 24 goto find_lock");
        assertThat(bat).doesNotContain("\"jk = \"").doesNotContain("sha256 = ");
        // The JVM client's launcher counts as installed, and a jk on PATH is tried before a download.
        assertThat(bat).contains("%BIN_DIR%\\jk.bat").contains("'where jk 2^>nul'");
        assertThat(bat.indexOf("'where jk 2^>nul'")).isLessThan(bat.indexOf(":bootstrap"));
        assertThat(bat).contains("%BIN_DIR%\\jk.exe").contains("%USERPROFILE%\\.jk");
        assertThat(bat).doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Windows wrapper matches install.ps1: .zip (no system xz). Not .exe.zip.
        assertThat(bat).contains("set \"FILE=jk-windows-!HOST_ARCH!-!VERSION!.zip\"");
        // The signed sums, read before any client is fetched, decide native or JVM: the native
        // client when they list one for this host, else (or with JK_CLIENT=jvm, as install.ps1
        // reads it) the JVM client's jar, verified the same way, which writes its own launcher.
        assertThat(bat.indexOf("call :verify_signature JK_WRAPPER_TMP SHA256SUMS"))
                .isLessThan(bat.indexOf("'jk-windows-'"))
                .isLessThan(bat.indexOf(":download"));
        assertThat(bat).contains("set \"CLIENT_KIND=%JK_CLIENT%\"").doesNotContain("==\"ARM64\"");
        assertThat(bat).contains("set \"FILE=jk-!VERSION!.jar\"").contains("self write-launcher");
        assertThat(occurrences(bat, "Get-FileHash -Algorithm SHA256")).isEqualTo(1);
        // One download per home: the lock is taken before the pointer is read, and the install is
        // checked again once it is held.
        assertThat(bat).contains("set \"JK_WRAPPER_LOCK=%BIN_DIR%\\.jk-wrapper.lock\"");
        assertThat(bat.indexOf(":lock_acquired")).isLessThan(bat.indexOf("'/latest/'"));
        assertThat(bat).contains("call :installed_client && goto unlock_and_run");
        assertThat(bat).contains("JK_HOME must be an absolute path");
        assertThat(bat).doesNotContain(".exe.zip").doesNotContain(".xz");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }

    /**
     * The Windows wrapper reads three values it does not control — the latest {@code VERSION}
     * from the release host, the {@code jk-min} floor from the repository's lock, and the
     * installed {@code VERSION} file — and every PowerShell snippet it runs is a command string.
     * A value spliced into one could end a quoted literal and run code before the signature is
     * ever checked, so each is checked to be a version token first and then handed over through
     * the environment only.
     */
    @Test
    void windows_wrapper_validates_untrusted_values_and_never_splices_them_into_powershell() throws Exception {
        String bat = template("jk.bat");
        // The gate: `set` prints the value raw into a whole-line findstr match, letters/digits/._- only.
        // No temp file: wrappers started in the same second share %RANDOM%.
        assertThat(bat)
                .contains("set JK_WRAPPER_TOKEN | findstr /r /x /c:\"JK_WRAPPER_TOKEN=[0-9A-Za-z._-][0-9A-Za-z._-]*\"");
        assertThat(bat).doesNotContain("jk-wrapper-token-");
        // Every untrusted value passes the gate before anything uses it. A bad installed VERSION
        // sends the wrapper on to a fresh client rather than stopping it.
        for (String value : List.of("VERSION", "FLOOR")) {
            assertThat(bat).as("%s is checked", value).contains("call :require_version_token " + value + " ");
        }
        assertThat(bat).contains("call :is_version_token INSTALLED || exit /b 1");
        assertThat(bat.indexOf("call :require_version_token VERSION"))
                .as("VERSION is checked before it names a download")
                .isLessThan(bat.indexOf("$env:JK_WRAPPER_VERSION"));
        assertThat(bat.indexOf("call :require_version_token FLOOR"))
                .as("FLOOR is checked before the first version compare")
                .isLessThan(bat.indexOf("call :version_ge"));
        assertThat(bat.indexOf("call :is_version_token INSTALLED"))
                .as("INSTALLED is checked before it is compared")
                .isLessThan(bat.indexOf("call :version_ge INSTALLED"));
        // No PowerShell command line carries a wrapper variable; values travel as $env:.
        Pattern spliced = Pattern.compile(
                "[%!](VERSION|FLOOR|INSTALLED|FILE|JK_WRAPPER_TMP|JK_WRAPPER_PTMP|JK_RELEASES_URL)[%!]");
        for (String line : bat.split("\\R")) {
            if (!line.contains("powershell")) continue;
            assertThat(spliced.matcher(line).find())
                    .as("a wrapper variable is spliced into PowerShell text: %s", line)
                    .isFalse();
        }
        // %VAR% expands when cmd parses the line, before any check could run and with & | " live.
        // Only delayed expansion (!VAR!) is inert, so the untrusted values are never read that way.
        assertThat(bat).doesNotContain("%VERSION%").doesNotContain("%FLOOR%").doesNotContain("%INSTALLED%");
    }

    /**
     * {@code TMP} and {@code TEMP} are Windows' own temp variables. Every {@code powershell} child
     * the wrapper starts inherits them and writes its temp files wherever they point; the wrapper
     * removes its scratch directories while a child may still hold a file there. So the scratch
     * directories live in wrapper-prefixed variables, inside the download lock only its holder
     * writes, and the Windows ones are never set.
     */
    @Test
    void windows_wrapper_never_sets_the_temp_variables_its_children_inherit() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).doesNotContainPattern("(?i)set \\\"?TMP=").doesNotContainPattern("(?i)set \\\"?TEMP=");
        assertThat(bat).contains("set \"JK_WRAPPER_TMP=!JK_WRAPPER_LOCK!\\fetch\"");
        assertThat(bat).contains("set \"JK_WRAPPER_PTMP=!JK_WRAPPER_LOCK!\\latest\"");
        assertThat(bat).contains("rmdir /s /q \"!JK_WRAPPER_LOCK!\"");
        // The scratch path reaches PowerShell the same way every other wrapper value does.
        assertThat(bat).contains("$env:JK_WRAPPER_TMP").contains("$env:JK_WRAPPER_PTMP");
    }

    /**
     * {@code command -v jk} answers with the wrapper itself when {@code .} is on PATH, or when the
     * committed wrapper is symlinked or copied into a PATH directory. Exec-ing that answer is a
     * loop that never reaches the download. With no installed jk and no reachable release host,
     * every one of those shapes must end in the offline error instead.
     */
    @Test
    void posix_wrapper_never_execs_itself_when_jk_on_path_is_the_wrapper(@TempDir Path tmp) throws Exception {
        if (Os.isWindows()) return;
        Path repo = materialize(tmp.resolve("repo"));
        Path linkDir = Files.createDirectories(tmp.resolve("pathlink"));
        Symlinks.create(linkDir.resolve("jk"), repo.resolve("jk"));
        Path copyDir = Files.createDirectories(tmp.resolve("pathcopy"));
        Files.copy(repo.resolve("jk"), copyDir.resolve("jk"));
        Files.setPosixFilePermissions(copyDir.resolve("jk"), Files.getPosixFilePermissions(repo.resolve("jk")));

        for (Path onPath : List.of(repo, linkDir, copyDir)) {
            Run run = runWrapper(repo, tmp, onPath, "file://" + tmp.resolve("no-such-releases"));
            assertThat(run.exit())
                    .as("PATH=%s: exits instead of looping", onPath)
                    .isEqualTo(1);
            assertThat(run.stderr())
                    .as("PATH=%s", onPath)
                    .contains("could not read file://" + tmp.resolve("no-such-releases") + "/latest/LATEST");
        }
    }

    @Test
    void posix_wrapper_prefers_a_real_jk_on_path_over_a_download(@TempDir Path tmp) throws Exception {
        if (Os.isWindows()) return;
        Path repo = materialize(tmp.resolve("repo"));
        Path realBin = Files.createDirectories(tmp.resolve("realbin"));
        Path real = realBin.resolve("jk");
        Files.writeString(real, "#!/bin/sh\necho \"real jk: $*\"\n");
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwxr-xr-x"));

        Run run = runWrapper(repo, tmp, realBin, "file://" + tmp.resolve("no-such-releases"));
        assertThat(run.exit()).isZero();
        assertThat(run.stdout()).contains("real jk: --version");
    }

    /**
     * The latest-release pointer is signed data. A pointer that cannot be read stops with its URL;
     * one whose body is not exactly {@code version <v>} / {@code issued <n>} is refused after its
     * signature verifies; one signed by another key is refused before its body is read. So a
     * hostile body never names a download, whatever it says, and a verified pointer names exactly
     * the version it carries.
     */
    @Test
    void posix_wrapper_refuses_an_unreachable_malformed_or_foreign_signed_pointer(@TempDir Path tmp) throws Exception {
        if (Os.isWindows()) return;
        KeyPair release = rsa3072();
        Path repo = materialize(tmp.resolve("repo"), spki(release));
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));

        Run offline = runWrapper(repo, tmp, empty, "file://" + tmp.resolve("no-such-releases"));
        assertThat(offline.exit()).isEqualTo(1);
        assertThat(offline.stderr())
                .contains("could not read")
                .contains("latest/LATEST")
                .doesNotContain("fetching jk");

        Path latest = Files.createDirectories(tmp.resolve("releases/latest"));
        String releases = "file://" + tmp.resolve("releases");

        signedPointer(latest, "\n", release);
        Run blank = runWrapper(repo, tmp, empty, releases);
        assertThat(blank.exit()).isEqualTo(1);
        assertThat(blank.stderr()).contains("is not a release pointer").doesNotContain("fetching jk");

        signedPointer(latest, "version 1.0'; echo pwned; '\nissued 1\n", release);
        Run hostile = runWrapper(repo, tmp, empty, releases);
        assertThat(hostile.exit()).isEqualTo(1);
        assertThat(hostile.stderr()).contains("is not a release pointer").doesNotContain("fetching jk");
        assertThat(hostile.stdout()).doesNotContain("pwned");

        signedPointer(latest, "version 1.0.0\nissued 1\n", rsa3072());
        Run foreign = runWrapper(repo, tmp, empty, releases);
        assertThat(foreign.exit()).isEqualTo(1);
        assertThat(foreign.stderr()).contains("signature verification failed").doesNotContain("fetching jk");

        signedPointer(latest, "version 1.0.0\nissued 1\n", release);
        Run named = runWrapper(repo, tmp, empty, releases);
        assertThat(named.stderr())
                .as("a verified pointer names the version to fetch")
                .contains("fetching jk 1.0.0");
    }

    /** A wrapper in a module directory reads the workspace lock above it, as {@code ./jk} does. */
    @Test
    void windows_wrapper_reads_the_floor_from_the_nearest_lock_above_it(@TempDir Path tmp) throws Exception {
        if (!Os.isWindows()) return;
        Path module = materializeBat(tmp.resolve("ws/module"));
        Files.writeString(tmp.resolve("ws/jk-lock.toml"), "version = 1\njk-min = \"9.0.0\"\n");
        Path home = installedJvmClient(tmp, "1.0.0");
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));

        Run below = runBat(module, tmp, empty, home);
        assertThat(below.exit()).isEqualTo(1);
        assertThat(below.stdout()).doesNotContain("installed jk");
        assertThat(below.stderr()).contains("could not read").contains("latest/LATEST");

        Files.writeString(home.resolve("bin/VERSION"), "9.1.0\r\n");
        Run meets = runBat(module, tmp, empty, home);
        assertThat(meets.exit()).isZero();
        assertThat(meets.stdout()).contains("installed jk: --version");
    }

    /**
     * {@code where jk} answers with the wrapper itself when the wrapper's directory is on PATH, or
     * with a copy of it in a PATH directory. Running that answer never reaches the download.
     */
    @Test
    void windows_wrapper_never_runs_itself_when_jk_on_path_is_the_wrapper(@TempDir Path tmp) throws Exception {
        if (!Os.isWindows()) return;
        Path repo = materializeBat(tmp.resolve("repo"));
        Path copyDir = Files.createDirectories(tmp.resolve("pathcopy"));
        Files.copy(repo.resolve("jk.bat"), copyDir.resolve("jk.bat"));
        Path home = Files.createDirectories(tmp.resolve("home"));

        for (Path onPath : List.of(repo, copyDir)) {
            Run run = runBat(repo, tmp, onPath, home);
            assertThat(run.exit())
                    .as("PATH=%s: exits instead of looping", onPath)
                    .isEqualTo(1);
            assertThat(run.stderr())
                    .as("PATH=%s", onPath)
                    .contains("could not read")
                    .contains("latest/LATEST");
        }
    }

    @Test
    void windows_wrapper_prefers_a_real_jk_on_path_over_a_download(@TempDir Path tmp) throws Exception {
        if (!Os.isWindows()) return;
        Path repo = materializeBat(tmp.resolve("repo"));
        Path realBin = Files.createDirectories(tmp.resolve("realbin"));
        Files.writeString(realBin.resolve("jk.bat"), "@echo real jk: %*\r\n");

        Run nothingInstalled = runBat(repo, tmp, realBin, Files.createDirectories(tmp.resolve("empty-home")));
        assertThat(nothingInstalled.exit()).isZero();
        assertThat(nothingInstalled.stdout()).contains("real jk: --version");

        // The installed client the floor rejects is skipped, even when its directory is on PATH too.
        Files.writeString(repo.resolve("jk-lock.toml"), "version = 1\njk-min = \"9.0.0\"\n");
        Path home = installedJvmClient(tmp, "1.0.0");
        Run belowFloor = runBat(repo, tmp, home.resolve("bin") + ";" + realBin, home);
        assertThat(belowFloor.exit()).isZero();
        assertThat(belowFloor.stdout()).contains("real jk: --version").doesNotContain("installed jk");
    }

    /**
     * An installed {@code VERSION} file that is not a version proves nothing about the floor: the
     * wrapper moves on to a fresh client instead of stopping, and the value never runs as a command.
     */
    @Test
    void windows_wrapper_moves_past_an_installed_version_file_that_is_not_a_version(@TempDir Path tmp)
            throws Exception {
        if (!Os.isWindows()) return;
        Path repo = materializeBat(tmp.resolve("repo"));
        Files.writeString(repo.resolve("jk-lock.toml"), "version = 1\njk-min = \"1.0.0\"\n");
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));

        for (String version : List.of("9.0.0 beta", "9.0.0&echo pwned")) {
            Path home = installedJvmClient(tmp, version);
            Run run = runBat(repo, tmp, empty, home);
            assertThat(run.exit()).as("VERSION=%s", version).isEqualTo(1);
            assertThat(run.stdout())
                    .as("VERSION=%s", version)
                    .doesNotContain("installed jk")
                    .doesNotContain("pwned");
            assertThat(run.stderr())
                    .as("VERSION=%s", version)
                    .doesNotContain("is not a version")
                    .contains("could not read")
                    .contains("latest/LATEST");
        }
    }

    /**
     * A wrapper that finds the download lock held waits for it, then runs the jk its holder
     * installed instead of downloading again. The holder here is a live process on another host,
     * so only the lock's age could break it.
     */
    @Test
    void posix_wrapper_waits_on_the_download_lock_then_runs_what_its_holder_installed(@TempDir Path tmp)
            throws Exception {
        if (Os.isWindows()) return;
        Path repo = materialize(tmp.resolve("repo"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path lock = Files.createDirectories(home.resolve("bin/.jk-wrapper.lock"));
        Files.writeString(lock.resolve("owner"), ProcessHandle.current().pid() + " elsewhere\n");
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));

        Process waiter = startWrapper(repo, empty, "file://" + tmp.resolve("no-such-releases"), home);
        Thread.sleep(2_000);
        assertThat(waiter.isAlive())
                .as("the wrapper waits while the lock is held")
                .isTrue();
        Path installed = home.resolve("bin/jk");
        Files.writeString(installed, "#!/bin/sh\necho \"installed jk: $*\"\n");
        Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.delete(lock.resolve("owner"));
        Files.delete(lock);

        Run run = finish(waiter);
        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.stderr()).contains("waiting for it").doesNotContain("could not read");
        assertThat(run.stdout()).contains("installed jk: --version");
        assertThat(lock).doesNotExist();
    }

    /**
     * A release whose signed sums list no native client for this host gets the JVM client: the
     * wrapper installs its jar under {@code lib/jk}, lets it write {@code bin/jk}, and execs that.
     * The next run finds it installed and downloads nothing. {@code JK_CLIENT=native} refuses.
     */
    @Test
    void posix_wrapper_installs_the_jvm_client_where_the_release_lists_no_native_one(@TempDir Path tmp)
            throws Exception {
        if (Os.isWindows()) return;
        KeyPair key = rsa3072();
        Path repo = materialize(tmp.resolve("repo"), spki(key));
        String releases = jvmClientRelease(tmp.resolve("releases"), "1.0.0", key, tmp);
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Map<String, String> jvm = Map.of("JK_JAVA_HOME", System.getProperty("java.home"));

        Run first = finish(startWrapper(repo, empty, releases, home, jvm));
        assertThat(first.exit()).as(first.stderr()).isZero();
        assertThat(first.stderr()).contains("client; installing the JVM client");
        assertThat(first.stdout()).contains("jvm jk: --version");
        assertThat(home.resolve("lib/jk/jk-1.0.0.jar")).isRegularFile();
        assertThat(home.resolve("bin/VERSION")).content().isEqualToIgnoringWhitespace("1.0.0");
        assertThat(home.resolve("bin/.jk-wrapper.lock")).doesNotExist();

        Run second = finish(startWrapper(repo, empty, releases, home, jvm));
        assertThat(second.exit()).isZero();
        assertThat(second.stderr()).doesNotContain("fetching");
        assertThat(second.stdout()).contains("jvm jk: --version");
        assertThat(home.resolve("launchers-written")).content().isEqualTo("x");

        Path refusedHome = Files.createDirectories(tmp.resolve("home-native"));
        Run refused = finish(startWrapper(repo, empty, releases, refusedHome, Map.of("JK_CLIENT", "native")));
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.stderr()).contains("client; unset JK_CLIENT to use the JVM client");
        assertThat(refusedHome.resolve("lib/jk")).doesNotExist();
        assertThat(refusedHome.resolve("bin/.jk-wrapper.lock")).doesNotExist();
    }

    /** A lock whose owner on this host is gone is broken at once, not waited on for ten minutes. */
    @Test
    void posix_wrapper_breaks_a_lock_whose_owner_is_gone(@TempDir Path tmp) throws Exception {
        if (Os.isWindows()) return;
        Path repo = materialize(tmp.resolve("repo"));
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path lock = Files.createDirectories(home.resolve("bin/.jk-wrapper.lock"));
        Process gone = new ProcessBuilder(Sleepers.exits(0)).start();
        assertThat(gone.waitFor(10, TimeUnit.SECONDS)).isTrue();
        Process uname = new ProcessBuilder("uname", "-n").start();
        String host = new String(uname.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        Files.writeString(lock.resolve("owner"), gone.pid() + " " + host + "\n");

        String releases = "file://" + tmp.resolve("no-such-releases");
        Run run = finish(startWrapper(repo, Files.createDirectories(tmp.resolve("empty-path")), releases, home));
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stderr()).contains("waiting for it").contains("could not read " + releases + "/latest/LATEST");
        assertThat(lock).doesNotExist();
    }

    /**
     * A release whose signed sums list no native client for this host gets the JVM client, whatever
     * the architecture: the wrapper installs its jar, verified against the sums, under
     * {@code lib\jk}, lets it write {@code bin\jk.bat}, and runs that. The next run finds it
     * installed and downloads nothing. {@code JK_CLIENT=native} refuses instead.
     */
    @Test
    void windows_wrapper_installs_the_jvm_client_where_the_release_lists_no_native_one(@TempDir Path tmp)
            throws Exception {
        if (!Os.isWindows()) return;
        KeyPair key = rsa3072();
        Path repo = materializeBat(tmp.resolve("repo"), key);
        String releases = jvmClientRelease(tmp.resolve("releases"), "1.0.0", key, tmp);
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));
        String java = System.getProperty("java.home");

        for (String[] host : new String[][] {{"ARM64", "aarch64"}, {"AMD64", "x86_64"}, {"SPARC", "sparc"}}) {
            Path home = Files.createDirectories(tmp.resolve("home-" + host[1]));
            Map<String, String> env =
                    Map.of("JK_RELEASES_URL", releases, "PROCESSOR_ARCHITECTURE", host[0], "JK_JAVA_HOME", java);

            Run first = finish(startBat(repo, tmp, empty.toString(), home, env));
            assertThat(first.exit()).as(first.stderr()).isZero();
            assertThat(first.stderr())
                    .contains("publishes no native windows-" + host[1] + " client; installing the JVM client");
            assertThat(first.stdout()).contains("jvm jk: --version");
            assertThat(home.resolve("lib/jk/jk-1.0.0.jar")).isRegularFile();
            assertThat(home.resolve("bin/VERSION")).content().isEqualToIgnoringWhitespace("1.0.0");
            assertThat(home.resolve("bin/.jk-wrapper.lock")).doesNotExist();

            Run second = finish(startBat(repo, tmp, empty.toString(), home, env));
            assertThat(second.exit()).isZero();
            assertThat(second.stderr()).doesNotContain("fetching");
            assertThat(second.stdout()).contains("jvm jk: --version");
            assertThat(home.resolve("launchers-written")).content().isEqualTo("x");
        }

        Path refusedHome = Files.createDirectories(tmp.resolve("home-native"));
        Run refused = finish(startBat(
                repo,
                tmp,
                empty.toString(),
                refusedHome,
                Map.of("JK_RELEASES_URL", releases, "PROCESSOR_ARCHITECTURE", "ARM64", "JK_CLIENT", "native")));
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.stderr())
                .contains("publishes no native windows-aarch64 client; unset JK_CLIENT to use the JVM client");
        assertThat(refusedHome.resolve("lib/jk")).doesNotExist();
        assertThat(refusedHome.resolve("bin/.jk-wrapper.lock")).doesNotExist();
    }

    /**
     * Wrappers started together on a home with no jk download once: the first takes the lock and
     * installs, the rest wait on it and then run what it installed.
     */
    @Test
    void windows_wrappers_started_together_download_once(@TempDir Path tmp) throws Exception {
        if (!Os.isWindows()) return;
        KeyPair key = rsa3072();
        Path repo = materializeBat(tmp.resolve("repo"), key);
        String releases = jvmClientRelease(tmp.resolve("releases"), "1.0.0", key, tmp);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));
        Map<String, String> jvm = Map.of(
                "JK_RELEASES_URL", releases, "JK_CLIENT", "jvm", "JK_JAVA_HOME", System.getProperty("java.home"));

        List<Process> started = new ArrayList<>();
        for (int i = 0; i < 4; i++) started.add(startBat(repo, tmp, empty.toString(), home, jvm));
        List<Run> runs = new ArrayList<>();
        for (Process p : started) runs.add(finish(p));

        for (Run run : runs) {
            assertThat(run.exit()).as(run.stderr()).isZero();
            assertThat(run.stdout()).contains("jvm jk: --version");
        }
        assertThat(runs.stream().filter(r -> r.stderr().contains("fetching")).count())
                .as("exactly one wrapper downloads")
                .isEqualTo(1);
        assertThat(home.resolve("launchers-written")).content().isEqualTo("x");
        assertThat(home.resolve("bin/.jk-wrapper.lock")).doesNotExist();
    }

    /** A lock left by a wrapper that died mid-download is broken, not waited on forever. */
    @Test
    void windows_wrapper_breaks_a_lock_whose_owner_is_gone(@TempDir Path tmp) throws Exception {
        if (!Os.isWindows()) return;
        KeyPair key = rsa3072();
        Path repo = materializeBat(tmp.resolve("repo"), key);
        String releases = jvmClientRelease(tmp.resolve("releases"), "1.0.0", key, tmp);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path lock = home.resolve("bin/.jk-wrapper.lock");
        Files.createDirectories(lock.resolve("fetch"));
        // A pid no live process has: the wrapper's owner check reads it as gone.
        Files.writeString(lock.resolve("owner"), "2147483644 0");

        Run run = finish(startBat(
                repo,
                tmp,
                tmp.resolve("empty-path").toString(),
                home,
                Map.of(
                        "JK_RELEASES_URL",
                        releases,
                        "JK_CLIENT",
                        "jvm",
                        "JK_JAVA_HOME",
                        System.getProperty("java.home"))));
        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.stderr()).contains("waiting for it").contains("fetching");
        assertThat(run.stdout()).contains("jvm jk: --version");
        assertThat(lock).doesNotExist();
    }

    private static int occurrences(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    private static KeyPair rsa3072() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(3072);
        return gen.generateKeyPair();
    }

    /** The public key as the wrapper bakes it in: base64 of the X.509 SubjectPublicKeyInfo. */
    private static String spki(KeyPair key) {
        return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    /**
     * One {@code LATEST} object: {@code body} plus a {@code signature} line over those bytes from
     * {@code signer}, laid out as sign-latest-pointer.sh writes a well-formed pointer.
     */
    private static void signedPointer(Path latestDir, String body, KeyPair signer) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(bytes);
        String object = body + "signature " + Base64.getEncoder().encodeToString(signature.sign()) + "\n";
        Files.writeString(latestDir.resolve("LATEST"), object);
        Files.deleteIfExists(latestDir.resolve("LATEST.sig"));
    }

    private record Run(int exit, String stdout, String stderr) {}

    /** The committed wrapper as {@code jk wrapper} writes it: {@code <dir>/jk}, executable. */
    private static Path materialize(Path dir) throws Exception {
        return materialize(dir, ReleaseVerifier.BUILT_IN_KEY);
    }

    /** As above, trusting {@code spki} as the release key so a test can sign what the wrapper reads. */
    private static Path materialize(Path dir, String spki) throws Exception {
        Files.createDirectories(dir);
        Path jk = dir.resolve("jk");
        Files.writeString(jk, template("jk.sh").replace(ReleaseVerifier.BUILT_IN_KEY, spki));
        Files.setPosixFilePermissions(jk, PosixFilePermissions.fromString("rwxr-xr-x"));
        return dir;
    }

    /**
     * Run {@code <repo>/jk --version} with {@code onPath} first on PATH, an empty {@code JK_HOME}
     * (nothing installed) and {@code releases} as the release host. A wrapper that loops is
     * killed after the wait and reported as such through the exit code.
     */
    private static Run runWrapper(Path repo, Path tmp, Path onPath, String releases) throws Exception {
        return finish(startWrapper(repo, onPath, releases, Files.createDirectories(tmp.resolve("home"))));
    }

    /** Start {@code <repo>/jk --version} with {@code onPath} first on PATH and {@code home} as {@code JK_HOME}. */
    private static Process startWrapper(Path repo, Path onPath, String releases, Path home) throws IOException {
        return startWrapper(repo, onPath, releases, home, Map.of());
    }

    /** As above, then {@code extra} over the environment. */
    private static Process startWrapper(Path repo, Path onPath, String releases, Path home, Map<String, String> extra)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder("./jk", "--version").directory(repo.toFile());
        pb.environment().put("PATH", onPath + ":/usr/bin:/bin");
        pb.environment().put("JK_HOME", home.toString());
        pb.environment().put("JK_RELEASES_URL", releases);
        pb.environment().remove("JK_CLIENT");
        pb.environment().remove("JK_JAVA_HOME");
        pb.environment().remove("JAVA_HOME");
        pb.environment().putAll(extra);
        return pb.start();
    }

    /** The committed Windows wrapper as {@code jk wrapper} writes it: {@code <dir>/jk.bat}. */
    private static Path materializeBat(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.bat"), template("jk.bat"));
        return dir;
    }

    /**
     * A fresh {@code JK_HOME} holding a JVM client launcher, {@code bin\jk.bat}, and a {@code VERSION}
     * file reading {@code version}.
     */
    private static Path installedJvmClient(Path tmp, String version) throws Exception {
        Path home = Files.createTempDirectory(tmp, "home");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve("jk.bat"), "@echo installed jk: %*\r\n");
        Files.writeString(bin.resolve("VERSION"), version + "\r\n");
        return home;
    }

    private static Run runBat(Path dir, Path tmp, Path onPath, Path home) throws Exception {
        return runBat(dir, tmp, onPath.toString(), home);
    }

    /** Run the Windows wrapper as {@link #startBat} starts it, against an unreachable release host. */
    private static Run runBat(Path dir, Path tmp, String onPath, Path home) throws Exception {
        return finish(startBat(
                dir,
                tmp,
                onPath,
                home,
                Map.of(
                        "JK_RELEASES_URL",
                        tmp.resolve("no-such-releases").toUri().toString())));
    }

    /**
     * Start {@code .\jk.bat --version} in {@code dir} under a minimal cmd environment: {@code onPath}
     * ahead of the Windows directories, {@code home} as {@code JK_HOME}, then {@code extra}.
     */
    private static Process startBat(Path dir, Path tmp, String onPath, Path home, Map<String, String> extra)
            throws IOException {
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path temp = Files.createDirectories(tmp.resolve("cmd-temp"));
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/d", "/c", ".\\jk.bat", "--version").directory(dir.toFile());
        var env = pb.environment();
        env.clear();
        env.put("SystemRoot", root);
        env.put(
                "PATH",
                onPath + ";" + root + "\\System32;" + root + ";" + root + "\\System32\\WindowsPowerShell\\v1.0");
        env.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        env.put("TEMP", temp.toString());
        env.put("TMP", temp.toString());
        env.put("USERPROFILE", home.toString());
        env.put("JK_HOME", home.toString());
        env.putAll(extra);
        return pb.start();
    }

    private static Run finish(Process p) throws Exception {
        byte[] out;
        byte[] err;
        try (var stdout = p.getInputStream();
                var stderr = p.getErrorStream()) {
            var outReader = CompletableFuture.supplyAsync(() -> readAll(stdout));
            var errReader = CompletableFuture.supplyAsync(() -> readAll(stderr));
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError(
                        "the wrapper did not finish in 120s — it is running itself or never got the lock");
            }
            out = outReader.get();
            err = errReader.get();
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    /**
     * A release host under {@code root} for version {@code version}, signed by {@code key}: the
     * {@code latest/LATEST} pointer, and a version directory holding a JVM client jar plus the
     * {@code SHA256SUMS} and {@code SHA256SUMS.sig} that cover it, and no native client. The jar's
     * {@code self write-launcher} writes a {@code bin/jk.bat} and a {@code bin/jk} that answer
     * {@code jvm jk: <args>}, and counts itself in {@code <home>/launchers-written}.
     */
    private static String jvmClientRelease(Path root, String version, KeyPair key, Path tmp) throws Exception {
        signedPointer(Files.createDirectories(root.resolve("latest")), "version " + version + "\nissued 1\n", key);
        Path dir = Files.createDirectories(root.resolve(version));
        String jarName = "jk-" + version + ".jar";
        byte[] jar = fakeClientJar(tmp.resolve("fake-client"));
        Files.write(dir.resolve(jarName), jar);
        String hex =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
        byte[] sums = (hex + "  " + jarName + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(dir.resolve("SHA256SUMS"), sums);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(sums);
        Files.writeString(dir.resolve("SHA256SUMS.sig"), Base64.getEncoder().encodeToString(signature.sign()) + "\n");
        return root.toUri().toString().replaceAll("/$", "");
    }

    /** A runnable jar standing in for the JVM client, compiled from source here. */
    private static byte[] fakeClientJar(Path work) throws Exception {
        Path src = Files.createDirectories(work.resolve("src")).resolve("FakeClient.java");
        Files.writeString(src, """
                import java.nio.file.*;
                public class FakeClient {
                    public static void main(String[] args) throws Exception {
                        if (args.length == 2 && args[0].equals("self") && args[1].equals("write-launcher")) {
                            Path home = Path.of(System.getenv("JK_HOME"));
                            Files.createDirectories(home.resolve("bin"));
                            Files.writeString(home.resolve("bin/jk.bat"), "@echo jvm jk: %*\\r\\n");
                            Path sh = home.resolve("bin/jk");
                            Files.writeString(sh, "#!/bin/sh\\necho \\"jvm jk: $*\\"\\n");
                            sh.toFile().setExecutable(true);
                            Files.writeString(home.resolve("launchers-written"), "x",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        }
                    }
                }
                """);
        Path classes = Files.createDirectories(work.resolve("classes"));
        var javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("the test JVM is a JDK").isNotNull();
        assertThat(javac.run(null, null, null, "-d", classes.toString(), src.toString()))
                .isZero();
        var bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeClient");
        try (var out = new JarOutputStream(bytes, manifest)) {
            out.putNextEntry(new JarEntry("FakeClient.class"));
            out.write(Files.readAllBytes(classes.resolve("FakeClient.class")));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The Windows wrapper trusting {@code key} as the release key. */
    private static Path materializeBat(Path dir, KeyPair key) throws Exception {
        var rsa = (RSAPublicKey) key.getPublic();
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.bat"),
                template("jk.bat")
                        .replace(ReleaseVerifier.BUILT_IN_RSA_MODULUS, unsignedBase64(rsa.getModulus()))
                        .replace(ReleaseVerifier.BUILT_IN_RSA_EXPONENT, unsignedBase64(rsa.getPublicExponent())));
        return dir;
    }

    /** Base64 of {@code n}'s big-endian magnitude, without the sign byte RSAParameters does not take. */
    private static String unsignedBase64(BigInteger n) {
        byte[] bytes = n.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
