// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Os;
import java.lang.foreign.MemoryLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Credential Manager backend over a fake {@code advapi32}, and the {@code CREDENTIALW} layout it hands it. */
class WindowsCredentialBackendTest {

    /** An in-memory credential set; {@code error} set to non-zero makes every call fail with it. */
    static final class FakeCalls implements WindowsCredentialBackend.Calls {
        final Map<String, byte[]> blobs = new HashMap<>();
        final Map<String, String> comments = new HashMap<>();
        final Map<String, String> users = new HashMap<>();
        final List<String> targets = new ArrayList<>();
        int error;

        @Override
        public WindowsCredentialBackend.ReadResult read(String target) {
            targets.add(target);
            if (error != 0) return new WindowsCredentialBackend.ReadResult(error, null);
            byte[] blob = blobs.get(target);
            return blob == null
                    ? new WindowsCredentialBackend.ReadResult(WindowsCredentialBackend.ERROR_NOT_FOUND, null)
                    : new WindowsCredentialBackend.ReadResult(0, blob.clone());
        }

        @Override
        public int write(String target, String comment, String userName, byte[] blob) {
            targets.add(target);
            if (error != 0) return error;
            blobs.put(target, blob.clone());
            comments.put(target, comment);
            users.put(target, userName);
            return 0;
        }

        @Override
        public int delete(String target) {
            targets.add(target);
            if (error != 0) return error;
            return blobs.remove(target) == null ? WindowsCredentialBackend.ERROR_NOT_FOUND : 0;
        }
    }

    /** A backend over an empty fake credential set. */
    static WindowsCredentialBackend fake() {
        return new WindowsCredentialBackend(new FakeCalls());
    }

    @Test
    void a_secret_is_stored_as_a_utf16_blob_under_the_jk_target_and_reads_back() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);

        backend.write("repo/nexus", "jk repo login nexus", "p@ss w0rd \"é€\"");

        assertThat(calls.blobs).containsOnlyKeys("jk:repo/nexus");
        assertThat(calls.blobs.get("jk:repo/nexus")).isEqualTo("p@ss w0rd \"é€\"".getBytes(StandardCharsets.UTF_16LE));
        assertThat(calls.comments.get("jk:repo/nexus")).isEqualTo("jk repo login nexus");
        assertThat(calls.users.get("jk:repo/nexus")).isEqualTo("jk");
        assertThat(backend.read("repo/nexus")).contains("p@ss w0rd \"é€\"");
    }

    @Test
    void not_found_is_an_absent_item_on_read_and_a_removed_one_on_delete() {
        WindowsCredentialBackend backend = new WindowsCredentialBackend(new FakeCalls());
        assertThat(backend.read("forge/github.com")).isEmpty();
        backend.delete("forge/github.com");
    }

    @Test
    void any_other_win32_error_is_a_refusal_naming_it() {
        FakeCalls calls = new FakeCalls();
        calls.error = 5;
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);

        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("Win32 error 5 (ERROR_ACCESS_DENIED)");
        assertThatThrownBy(() -> backend.write("repo/nexus", "l", "s"))
                .hasMessageContaining("refused to store repo/nexus");
        assertThatThrownBy(() -> backend.delete("repo/nexus")).hasMessageContaining("refused to remove repo/nexus");
    }

    @Test
    void the_probe_reads_a_target_no_item_has_and_tells_no_credential_set_from_a_refusal() {
        FakeCalls calls = new FakeCalls();
        assertThat(new WindowsCredentialBackend(calls).probe().available()).isTrue();
        assertThat(calls.targets).containsExactly("jk:probe");
        assertThat(calls.blobs).isEmpty();

        calls.error = WindowsCredentialBackend.ERROR_NO_SUCH_LOGON_SESSION;
        SecretBackend.Probe noSet = new WindowsCredentialBackend(calls).probe();
        assertThat(noSet.available()).isFalse();
        assertThat(noSet.refused()).isFalse();
        assertThat(noSet.reason()).contains("no credential set").contains("1312");

        calls.error = 5;
        SecretBackend.Probe denied = new WindowsCredentialBackend(calls).probe();
        assertThat(denied.refused()).isTrue();
        assertThat(denied.reason()).contains("ERROR_ACCESS_DENIED");
    }

    @Test
    void a_secret_longer_than_the_credential_blob_limit_is_refused_before_any_call() {
        FakeCalls calls = new FakeCalls();
        String token = "x".repeat(WindowsCredentialBackend.MAX_BLOB_BYTES / 2 + 1);
        assertThatThrownBy(() -> new WindowsCredentialBackend(calls).write("repo/nexus", "l", token))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("at most 1280");
        assertThat(calls.targets).isEmpty();
        new WindowsCredentialBackend(calls).write("repo/nexus", "l", "x".repeat(1280));
        assertThat(calls.blobs).containsKey("jk:repo/nexus");
    }

    @Test
    void off_windows_the_probe_says_there_is_no_credential_manager_and_calls_refuse() {
        assumeFalse(Os.isWindows(), "off Windows only");
        WindowsCredentialBackend backend = new WindowsCredentialBackend();
        SecretBackend.Probe probe = backend.probe();
        assertThat(probe.available()).isFalse();
        assertThat(probe.refused()).isFalse();
        assertThat(probe.reason()).contains("only on Windows");
        assertThatThrownBy(() -> backend.read("repo/nexus")).isInstanceOf(SecretBackendException.class);
    }

    @Test
    void the_credentialw_layout_matches_the_64_bit_windows_abi() {
        assertThat(Advapi32Credentials.CREDENTIAL.byteSize()).isEqualTo(80);
        assertThat(offset("Flags")).isZero();
        assertThat(offset("Type")).isEqualTo(4);
        assertThat(offset("TargetName")).isEqualTo(8);
        assertThat(offset("Comment")).isEqualTo(16);
        assertThat(offset("LastWrittenLow")).isEqualTo(24);
        assertThat(offset("CredentialBlobSize")).isEqualTo(32);
        assertThat(offset("CredentialBlob")).isEqualTo(40);
        assertThat(offset("Persist")).isEqualTo(48);
        assertThat(offset("AttributeCount")).isEqualTo(52);
        assertThat(offset("Attributes")).isEqualTo(56);
        assertThat(offset("TargetAlias")).isEqualTo(64);
        assertThat(offset("UserName")).isEqualTo(72);
    }

    private static long offset(String field) {
        return Advapi32Credentials.CREDENTIAL.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }
}
