// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateArtifactTest {

    private static final String HASH = "a".repeat(64);

    private static final String V = "0.12.0";

    @Test
    void prefers_xz_on_every_os() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n"
                + HASH + "  jk-linux-x86_64-0.12.0.xz\n"
                + HASH + "  jk-windows-x86_64-0.12.0.xz\n"
                + HASH + "  jk-windows-x86_64-0.12.0.zip\n"
                + HASH + "  jk-macos-aarch64-0.12.0.xz\n";
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64", V))
                .isEqualTo("jk-linux-x86_64-0.12.0.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64", V))
                .isEqualTo("jk-windows-x86_64-0.12.0.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "macos", "aarch64", V))
                .isEqualTo("jk-macos-aarch64-0.12.0.xz");
    }

    @Test
    void windows_falls_back_to_zip_when_sums_have_no_xz() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-windows-x86_64-0.12.0.zip\n";
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64", V))
                .isEqualTo("jk-windows-x86_64-0.12.0.zip");
    }

    /**
     * The rollback shape: a valid, signed manifest from an older release served under a newer
     * version's directory. Its artifact names carry the older version, so a request for the newer
     * one finds nothing to verify against and refuses.
     */
    @Test
    void a_manifest_from_another_release_satisfies_no_request_for_this_one() {
        String older = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-linux-x86_64-0.12.0.xz\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(older, "linux", "x86_64", "0.13.0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64-0.13.0.xz");
    }

    /**
     * A JVM client asks for its host's native client on every update and moves to it once a
     * release lists one; until then the answer is empty, not a refusal.
     */
    @Test
    void a_native_client_is_offered_only_when_the_release_lists_one_for_the_host() throws Exception {
        String jvmOnly = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-0.12.0.jar\n";
        assertThat(SelfCommand.UpdateSub.nativeClientArtifact(jvmOnly, "freebsd", "x86_64", V))
                .isEmpty();
        String withNative = jvmOnly + HASH + "  jk-freebsd-x86_64-0.12.0.xz\n";
        assertThat(SelfCommand.UpdateSub.nativeClientArtifact(withNative, "freebsd", "x86_64", V))
                .contains("jk-freebsd-x86_64-0.12.0.xz");
        String windowsZip = jvmOnly + HASH + "  jk-windows-aarch64-0.12.0.zip\n";
        assertThat(SelfCommand.UpdateSub.nativeClientArtifact(windowsZip, "windows", "aarch64", V))
                .contains("jk-windows-aarch64-0.12.0.zip");
    }

    /** The host's release name uses the vocabulary the installers derive from {@code uname}. */
    @Test
    void the_host_is_named_as_the_installers_name_it() {
        assertThat(SelfCommand.UpdateSub.releaseOs("Linux")).isEqualTo("linux");
        assertThat(SelfCommand.UpdateSub.releaseOs("Mac OS X")).isEqualTo("macos");
        assertThat(SelfCommand.UpdateSub.releaseOs("Windows 11")).isEqualTo("windows");
        assertThat(SelfCommand.UpdateSub.releaseOs("FreeBSD")).isEqualTo("freebsd");
        assertThat(SelfCommand.UpdateSub.releaseOs("SunOS")).isEqualTo("sunos");
        assertThat(SelfCommand.UpdateSub.releaseOs("")).isEqualTo("unknown");
        assertThat(SelfCommand.UpdateSub.releaseArch("amd64")).isEqualTo("x86_64");
        assertThat(SelfCommand.UpdateSub.releaseArch("arm64")).isEqualTo("aarch64");
        assertThat(SelfCommand.UpdateSub.releaseArch("aarch64")).isEqualTo("aarch64");
        assertThat(SelfCommand.UpdateSub.releaseArch("ppc64le")).isEqualTo("ppc64le");
    }

    @Test
    void the_jvm_client_is_the_platform_neutral_jar_and_only_when_the_release_ships_one() throws Exception {
        String sums = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-0.12.0.jar\n";
        assertThat(SelfCommand.UpdateSub.jvmClientArtifact(sums, V)).isEqualTo("jk-0.12.0.jar");
        String without = HASH + "  jk-engine-0.12.0.jar\n" + HASH + "  jk-linux-x86_64-0.12.0.xz\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.jvmClientArtifact(without, V))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-0.12.0.jar")
                .hasMessageContaining("no JVM client");
    }

    @Test
    void the_path_client_is_the_exe_then_the_bat_launcher_then_the_binary(@TempDir Path tmp) throws Exception {
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk"));
        Files.writeString(tmp.resolve("jk.bat"), "@echo off\r\n");
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk.bat"));
        Files.writeString(tmp.resolve("jk.exe"), "MZ");
        assertThat(SelfCommand.UpdateSub.pathClient(tmp)).isEqualTo(tmp.resolve("jk.exe"));
    }

    @Test
    void ingest_client_reclaims_the_inflated_temp_binary(@TempDir Path tmp) throws Exception {
        // Cas.putFile copies (temp + atomic move) — without the delete, every successful
        // jk self update strands one native-binary-sized jk-self-*.bin in the system temp dir.
        Path client = Files.createTempFile(tmp, "jk-self-", ".bin");
        Files.write(client, new byte[] {1, 2, 3, 4});
        Cas cas = new Cas(tmp.resolve("cas"));
        String sha = SelfCommand.UpdateSub.ingestClient(cas, client);
        assertThat(cas.contains(sha)).isTrue();
        assertThat(client).doesNotExist();
    }

    @Test
    void unix_does_not_fall_back_to_zip() {
        String sums = HASH + "  jk-linux-x86_64-0.12.0.zip\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64", V))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64-0.12.0.xz");
    }
}
