// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.toolchain.WrapperCommand;
import cc.jumpkick.host.Os;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
        assertThat(sh).contains("latest/VERSION").contains("SHA256SUMS.sig");
        assertThat(sh).contains(ReleaseVerifier.BUILT_IN_KEY);
        assertThat(sh).contains("\"jk-min = \"*").contains("$SEARCH/jk-lock.toml");
        // Bin resolution mirrors install.sh/JkDirs: one home, one bin, no cascade to drift from.
        assertThat(sh).contains("$BIN_DIR/jk").contains("${JK_HOME:-$HOME/.jk}/bin");
        assertThat(sh).doesNotContain("XDG_").doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Downloads authenticate the manifest, then verify its exact artifact entry.
        assertThat(sh).contains("openssl dgst -sha256 -verify").contains("matches != 1");
        assertThat(sh).doesNotContain("\"jk = \"*").doesNotContain("sha256 = ");
        // Newest installed wins when it satisfies the floor; a stale channel is a hard error.
        assertThat(sh).contains("ver_ge").contains("requires jk >=");
        // Unix wrapper matches install.sh: .xz, inflated with system xz. No zip.
        assertThat(sh).contains("jk-$OS-$ARCH-$VERSION.xz").contains("xz -dc");
        // The same refusal JkDirs makes: a relative JK_HOME is not a home jk would read.
        assertThat(sh).contains("JK_HOME must be an absolute path");
        // A jk found on PATH is exec'd only when it is not this wrapper by identity or by content.
        assertThat(sh).contains("real_path \"$PATH_JK\"").contains("is_wrapper \"$PATH_JK\"");
        // A failed or empty VERSION fetch is an error that names the URL, never a bare download.
        assertThat(sh).contains("could not read $RELEASES/latest/VERSION").contains("is empty");
        assertThat(sh).doesNotContain(".zip");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness.
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("latest/VERSION").contains("SHA256SUMS.sig");
        assertThat(bat).contains("RSASignaturePadding]::Pkcs1").contains("$count -ne 1");
        assertThat(bat).contains(ReleaseVerifier.BUILT_IN_RSA_MODULUS).contains(ReleaseVerifier.BUILT_IN_RSA_EXPONENT);
        assertThat(bat).contains("jk-min");
        assertThat(bat).doesNotContain("\"jk = \"").doesNotContain("sha256 = ");
        assertThat(bat).contains("%BIN_DIR%\\jk.exe").contains("%USERPROFILE%\\.jk");
        assertThat(bat).doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Windows wrapper matches install.ps1: .zip (no system xz). Not .exe.zip.
        assertThat(bat).contains("jk-windows-x86_64-!VERSION!.zip");
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
        // The gate: a findstr regex over the whole value, letters/digits/._- only.
        assertThat(bat).contains("findstr /r /c:\"^[0-9A-Za-z._-][0-9A-Za-z._-]*$\"");
        // Every untrusted value passes the gate before anything uses it.
        for (String value : List.of("VERSION", "FLOOR", "INSTALLED")) {
            assertThat(bat).as("%s is checked", value).contains("call :require_version_token " + value + " ");
        }
        assertThat(bat.indexOf("call :require_version_token VERSION"))
                .as("VERSION is checked before it names a download")
                .isLessThan(bat.indexOf("$env:JK_WRAPPER_VERSION"));
        assertThat(bat.indexOf("call :require_version_token FLOOR"))
                .as("FLOOR is checked before the first version compare")
                .isLessThan(bat.indexOf("call :version_ge"));
        assertThat(bat.indexOf("call :require_version_token INSTALLED"))
                .as("INSTALLED is checked before it is compared")
                .isLessThan(bat.indexOf("call :version_ge INSTALLED"));
        // No PowerShell command line carries a wrapper variable; values travel as $env:.
        Pattern spliced = Pattern.compile("[%!](VERSION|FLOOR|INSTALLED|FILE|TMP|JK_RELEASES_URL)[%!]");
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
        Files.createSymbolicLink(linkDir.resolve("jk"), repo.resolve("jk"));
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
                    .contains("could not read file://" + tmp.resolve("no-such-releases") + "/latest/VERSION");
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
     * A failed fetch of {@code latest/VERSION} names the URL and stops. In a pipeline the shell
     * saw only {@code tr}'s status, so an offline host carried an empty VERSION into a vacuous
     * floor check and asked the host for {@code jk-linux-x86_64-.xz}.
     */
    @Test
    void posix_wrapper_reports_an_unreachable_or_empty_version_instead_of_downloading(@TempDir Path tmp)
            throws Exception {
        if (Os.isWindows()) return;
        Path repo = materialize(tmp.resolve("repo"));
        Path empty = Files.createDirectories(tmp.resolve("empty-path"));

        Run offline = runWrapper(repo, tmp, empty, "file://" + tmp.resolve("no-such-releases"));
        assertThat(offline.exit()).isEqualTo(1);
        assertThat(offline.stderr()).contains("could not read").doesNotContain("fetching jk");

        Path releases = tmp.resolve("releases");
        Files.createDirectories(releases.resolve("latest"));
        Files.writeString(releases.resolve("latest/VERSION"), "\n");
        Run blank = runWrapper(repo, tmp, empty, "file://" + releases);
        assertThat(blank.exit()).isEqualTo(1);
        assertThat(blank.stderr()).contains("is empty").doesNotContain("fetching jk");

        Files.writeString(releases.resolve("latest/VERSION"), "1.0'; echo pwned; '\n");
        Run hostile = runWrapper(repo, tmp, empty, "file://" + releases);
        assertThat(hostile.exit()).isEqualTo(1);
        assertThat(hostile.stderr()).contains("is not a version").doesNotContain("fetching jk");
        assertThat(hostile.stdout()).doesNotContain("pwned");
    }

    private record Run(int exit, String stdout, String stderr) {}

    /** The committed wrapper as {@code jk wrapper} writes it: {@code <dir>/jk}, executable. */
    private static Path materialize(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path jk = dir.resolve("jk");
        Files.writeString(jk, template("jk.sh"));
        Files.setPosixFilePermissions(jk, PosixFilePermissions.fromString("rwxr-xr-x"));
        return dir;
    }

    /**
     * Run {@code <repo>/jk --version} with {@code onPath} first on PATH, an empty {@code JK_HOME}
     * (nothing installed) and {@code releases} as the release host. A wrapper that loops is
     * killed after the wait and reported as such through the exit code.
     */
    private static Run runWrapper(Path repo, Path tmp, Path onPath, String releases) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        ProcessBuilder pb = new ProcessBuilder("./jk", "--version").directory(repo.toFile());
        pb.environment().put("PATH", onPath + ":/usr/bin:/bin");
        pb.environment().put("JK_HOME", home.toString());
        pb.environment().put("JK_RELEASES_URL", releases);
        Process p = pb.start();
        byte[] out;
        byte[] err;
        try (var stdout = p.getInputStream();
                var stderr = p.getErrorStream()) {
            var outReader = CompletableFuture.supplyAsync(() -> readAll(stdout));
            var errReader = CompletableFuture.supplyAsync(() -> readAll(stderr));
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("the wrapper did not finish in 20s — it is exec-ing itself");
            }
            out = outReader.get();
            err = errReader.get();
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
