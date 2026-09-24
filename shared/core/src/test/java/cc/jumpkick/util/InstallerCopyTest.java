// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for the installer copies. {@code hosting/public/} is what the CDN serves to
 * {@code curl … | bash} and {@code irm … | iex}; the repo-root copies are the ones reviewed,
 * hand-run and documented. A fix landed in only one of a pair ships the web a different
 * installer from the one this repo tests, and nothing else notices.
 */
class InstallerCopyTest {

    @Test
    void cdn_copies_are_byte_identical_to_the_repo_root_installers() throws IOException {
        Path repo = findRepoRoot();

        for (String name : new String[] {"install.sh", "install.ps1"}) {
            Path root = repo.resolve(name);
            Path served = repo.resolve("hosting/public").resolve(name);
            assertThat(Files.readAllBytes(served))
                    .as("hosting/public/%s must be a byte-identical copy of %s", name, name)
                    .isEqualTo(Files.readAllBytes(root));
        }
    }

    @Test
    void every_install_path_retires_old_engines_before_materialization() throws IOException {
        Path repo = findRepoRoot();

        for (String name : new String[] {"install.sh", "install.ps1"}) {
            String installer = Files.readString(repo.resolve(name));
            String command = "self" + (name.endsWith(".sh") ? " retire-old-engines" : "\", \"retire-old-engines");
            assertThat(installer).containsOnlyOnce(command);
            assertThat(installer.indexOf(command))
                    .as("%s retires the old engine before the local-only materialization branch", name)
                    .isLessThan(installer.indexOf("product-lib engine"));
        }
    }

    @Test
    void remote_verification_precedes_every_installation_mutation() throws IOException {
        Path repo = findRepoRoot();
        String shell = Files.readString(repo.resolve("install.sh"));
        String powershell = Files.readString(repo.resolve("install.ps1"));

        assertThat(shell.indexOf("openssl dgst -sha256 -verify"))
                .isLessThan(shell.indexOf("park_if_present \"$JK_BIN\""));
        assertThat(powershell.lastIndexOf("Test-ReleaseEvidence"))
                .isLessThan(powershell.indexOf("Park-IfPresent $script:JkBin"));
    }

    @Test
    void java_and_installer_public_key_representations_match() throws Exception {
        Path repo = findRepoRoot();
        String java =
                Files.readString(repo.resolve("shared/client-io/src/main/java/cc/jumpkick/repo/ReleaseVerifier.java"));
        String shell = Files.readString(repo.resolve("install.sh"));
        String powershell = Files.readString(repo.resolve("install.ps1"));

        String spki = capture(java, "BUILT_IN_KEY\\s*=\\s*\"([^\"]+)\"");
        String modulus = capture(java, "BUILT_IN_RSA_MODULUS\\s*=\\s*\"([^\"]+)\"");
        String exponent = capture(java, "BUILT_IN_RSA_EXPONENT\\s*=\\s*\"([^\"]+)\"");
        assertThat(capture(shell, "RELEASE_RSA_SPKI=\"([^\"]+)\"")).isEqualTo(spki);
        assertThat(capture(powershell, "\\$ReleaseRsaModulus = \"([^\"]+)\"")).isEqualTo(modulus);
        assertThat(capture(powershell, "\\$ReleaseRsaExponent = \"([^\"]+)\"")).isEqualTo(exponent);

        var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spki)));
        assertThat(unsigned(key.getModulus().toByteArray()))
                .isEqualTo(Base64.getDecoder().decode(modulus));
        assertThat(unsigned(key.getPublicExponent().toByteArray()))
                .isEqualTo(Base64.getDecoder().decode(exponent));
    }

    /**
     * The installers refuse a signed latest-release pointer older than the release they ship
     * with, so the floor they embed is the declared version — bumped with it, or a freshly
     * published installer refuses the pointer it was published to read.
     */
    @Test
    void installers_embed_the_declared_version_as_the_pointer_floor() throws IOException {
        Path repo = findRepoRoot();
        String declared = capture(
                Files.readString(repo.resolve("shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java")),
                "VERSION\\s*=\\s*\"([^\"]+)\"");
        String shell = Files.readString(repo.resolve("install.sh"));
        String powershell = Files.readString(repo.resolve("install.ps1"));

        assertThat(capture(shell, "RELEASE_FLOOR=\"([^\"]+)\"")).isEqualTo(declared);
        assertThat(capture(powershell, "\\$ReleaseFloor = \"([^\"]+)\"")).isEqualTo(declared);
        // Verified before the version directory is named: the pointer check precedes the archive URL.
        assertThat(shell.indexOf("verify_signature \"$TMPDIR_JK/LATEST.body\""))
                .isLessThan(shell.indexOf("ARCHIVE_URL=\"$RELEASES_URL/$VERSION/"));
        assertThat(powershell.indexOf("Get-ReleasePointerVersion -Pointer"))
                .isLessThan(powershell.indexOf("$ArchiveUrl = \"$ReleasesUrl/$version/"));
        assertThat(shell).doesNotContain("latest/VERSION");
        assertThat(powershell).doesNotContain("latest/VERSION");
    }

    /**
     * {@code scripts/install.ps1} predates the repo-root entrypoint and was once a full duplicate
     * — a third copy to keep in step. It must stay a forwarder.
     */
    @Test
    void the_scripts_shim_forwards_instead_of_duplicating() throws IOException {
        Path repo = findRepoRoot();

        String shim = Files.readString(repo.resolve("scripts/install.ps1"));
        assertThat(shim).contains("install.ps1").doesNotContain("Invoke-WebRequest");
        assertThat(shim.lines().count()).isLessThan(20);
    }

    private static Path findRepoRoot() {
        return RepoRoot.find(InstallerCopyTest.class);
    }

    private static String capture(String text, String regex) {
        var matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        assertThat(matcher.find()).as("pattern %s", regex).isTrue();
        return matcher.group(1);
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
