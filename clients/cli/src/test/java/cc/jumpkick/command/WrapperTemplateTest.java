// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.toolchain.WrapperCommand;
import cc.jumpkick.host.Os;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
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
        assertThat(sh).contains("latest/LATEST").contains("SHA256SUMS.sig").doesNotContain("latest/LATEST.sig");
        // The pointer is signed data, verified before the version it names is used for anything.
        assertThat(sh.indexOf("verify_release_signature \"$TMP/LATEST.body\""))
                .isLessThan(sh.indexOf("jk-$OS-$ARCH-$VERSION.xz"));
        assertThat(sh).contains("/^version [0-9]+").contains("/^issued [0-9]+$/");
        assertThat(sh).doesNotContain("latest/VERSION");
        assertThat(sh).contains(ReleaseVerifier.BUILT_IN_KEY);
        assertThat(sh).contains("\"jk-min = \"*").contains("$SEARCH/jk-lock.toml");
        // Bin resolution mirrors install.sh/JkDirs: one home, one bin, no cascade to drift from.
        assertThat(sh).contains("$BIN_DIR/jk").contains("${JK_HOME:-$HOME/.jk}/bin");
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
        assertThat(sh).contains("jk-$OS-$ARCH-$VERSION.xz").contains("xz -dc");
        // The same refusal JkDirs makes: a relative JK_HOME is not a home jk would read.
        assertThat(sh).contains("JK_HOME must be an absolute path");
        // A jk found on PATH is exec'd only when it is not this wrapper by identity or by content.
        assertThat(sh).contains("real_path \"$PATH_JK\"").contains("is_wrapper \"$PATH_JK\"");
        // A failed or empty VERSION fetch is an error that names the URL, never a bare download.
        assertThat(sh).contains("could not read $RELEASES/latest/LATEST").contains("is not a release pointer");
        // A release file the host does not serve is refused by name, not with curl's bare status.
        assertThat(sh).contains("could not read $RELEASES/$VERSION/$NAME");
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
     * {@code rmdir}s its scratch directory while a child may still hold a file there. So the scratch
     * directory lives in wrapper-prefixed variables and the Windows ones are only ever read.
     */
    @Test
    void windows_wrapper_never_sets_the_temp_variables_its_children_inherit() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).doesNotContainPattern("(?i)set \\\"?TMP=").doesNotContainPattern("(?i)set \\\"?TEMP=");
        assertThat(bat).contains("set \"JK_WRAPPER_TMP=%TEMP%\\").contains("rmdir /s /q \"!JK_WRAPPER_TMP!\"");
        assertThat(bat).contains("set \"JK_WRAPPER_PTMP=%TEMP%\\").contains("rmdir /s /q \"!JK_WRAPPER_PTMP!\"");
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
