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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Credential Manager backend over a fake {@code advapi32}, and the {@code CREDENTIALW} layout it hands it. */
class WindowsCredentialBackendTest {

    /** An in-memory credential set; {@code error} set to non-zero makes every call fail with it. */
    static final class FakeCalls implements WindowsCredentialBackend.Calls {
        final Map<String, byte[]> blobs = new HashMap<>();
        final Map<String, String> comments = new HashMap<>();
        final Map<String, String> users = new HashMap<>();
        final List<String> targets = new ArrayList<>();
        final Set<String> refuseWrites = new HashSet<>();
        final Set<String> refuseDeletes = new HashSet<>();
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
            if (refuseWrites.contains(target)) return 5;
            blobs.put(target, blob.clone());
            comments.put(target, comment);
            users.put(target, userName);
            return 0;
        }

        @Override
        public int delete(String target) {
            targets.add(target);
            if (error != 0) return error;
            if (refuseDeletes.contains(target)) return 5;
            return blobs.remove(target) == null ? WindowsCredentialBackend.ERROR_NOT_FOUND : 0;
        }
    }

    /** A backend over an empty fake credential set. */
    static WindowsCredentialBackend fake() {
        return new WindowsCredentialBackend(new FakeCalls());
    }

    @Test
    void a_secret_is_stored_as_a_utf8_blob_under_the_jk_target_and_reads_back() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);

        backend.write("repo/nexus", "jk repo login nexus", "p@ss w0rd \"é€\"");

        assertThat(calls.blobs).containsOnlyKeys("jk:repo/nexus");
        assertThat(calls.blobs.get("jk:repo/nexus")).isEqualTo("p@ss w0rd \"é€\"".getBytes(StandardCharsets.UTF_8));
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
    void a_token_of_2560_characters_fits_in_one_credential() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        String jwt = "eyJ" + "a".repeat(WindowsCredentialBackend.MAX_BLOB_BYTES - 3);

        backend.write("repo/nexus", "l", jwt);

        assertThat(calls.blobs).containsOnlyKeys("jk:repo/nexus");
        assertThat(calls.blobs.get("jk:repo/nexus")).hasSize(2560);
        assertThat(backend.read("repo/nexus")).contains(jwt);
    }

    @Test
    void a_longer_secret_is_split_into_numbered_parts_under_a_head_and_reads_back_whole() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        String jwt = token(10_000);

        backend.write("repo/nexus", "jk repo login nexus", jwt);

        assertThat(calls.blobs)
                .containsOnlyKeys(
                        "jk:repo/nexus", "jk:repo/nexus#1", "jk:repo/nexus#2", "jk:repo/nexus#3", "jk:repo/nexus#4");
        assertThat(calls.blobs.get("jk:repo/nexus#1")).hasSize(2560);
        assertThat(calls.blobs.get("jk:repo/nexus#4")).hasSize(10_000 - 3 * 2560);
        byte[] head = Objects.requireNonNull(calls.blobs.get("jk:repo/nexus"));
        assertThat(head[0]).isEqualTo(WindowsCredentialBackend.HEAD_MARK);
        assertThat(new String(head, 1, head.length - 1, StandardCharsets.US_ASCII))
                .startsWith("parts=4 bytes=10000 sha256=");
        assertThat(calls.users.values()).containsOnly("jk");
        assertThat(calls.comments.values()).containsOnly("jk repo login nexus");
        assertThat(backend.read("repo/nexus")).contains(jwt);
    }

    @Test
    void the_parts_are_written_before_the_head_that_names_them() {
        FakeCalls calls = new FakeCalls();
        new WindowsCredentialBackend(calls).write("repo/nexus", "l", token(6000));

        List<String> writes = new ArrayList<>(calls.targets);
        writes.removeIf(t -> !calls.blobs.containsKey(t));
        assertThat(writes)
                .containsSubsequence("jk:repo/nexus#1", "jk:repo/nexus#2", "jk:repo/nexus#3", "jk:repo/nexus");
        assertThat(calls.targets.lastIndexOf("jk:repo/nexus")).isGreaterThan(calls.targets.indexOf("jk:repo/nexus#3"));
    }

    @Test
    void a_character_cut_between_two_parts_reads_back_whole() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        String secret = "a".repeat(2559) + "é€" + "b".repeat(3000);

        backend.write("repo/nexus", "l", secret);

        assertThat(calls.blobs.get("jk:repo/nexus#1")).hasSize(2560);
        assertThat(backend.read("repo/nexus")).contains(secret);
    }

    @Test
    void a_shorter_rewrite_removes_the_parts_it_no_longer_uses() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        backend.write("repo/nexus", "l", token(10_000));

        backend.write("repo/nexus", "l", token(6000));
        assertThat(calls.blobs)
                .containsOnlyKeys("jk:repo/nexus", "jk:repo/nexus#1", "jk:repo/nexus#2", "jk:repo/nexus#3");
        assertThat(backend.read("repo/nexus")).contains(token(6000));

        backend.write("repo/nexus", "l", "short");
        assertThat(calls.blobs).containsOnlyKeys("jk:repo/nexus");
        assertThat(backend.read("repo/nexus")).contains("short");
    }

    @Test
    void a_delete_removes_every_part_and_the_head() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        backend.write("repo/nexus", "l", token(10_000));
        backend.write("repo/other", "l", "kept");

        backend.delete("repo/nexus");

        assertThat(calls.blobs).containsOnlyKeys("jk:repo/other");
        assertThat(backend.read("repo/nexus")).isEmpty();
    }

    @Test
    void a_write_that_fails_partway_leaves_nothing_that_reads_as_the_item_and_a_delete_clears_it() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        backend.write("repo/nexus", "l", token(6000));
        calls.refuseWrites.add("jk:repo/nexus#3");

        assertThatThrownBy(() -> backend.write("repo/nexus", "l", token(10_000)))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("refused to store repo/nexus");

        assertThat(calls.blobs).doesNotContainKey("jk:repo/nexus");
        assertThat(backend.read("repo/nexus"))
                .as("neither the old secret nor half the new one")
                .isEmpty();
        calls.refuseWrites.clear();
        backend.delete("repo/nexus");
        assertThat(calls.blobs).isEmpty();
    }

    @Test
    void a_missing_or_altered_part_is_a_refusal_not_a_wrong_secret() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        backend.write("repo/nexus", "l", token(6000));

        byte[] part2 = Objects.requireNonNull(calls.blobs.remove("jk:repo/nexus#2"));
        assertThatThrownBy(() -> backend.read("repo/nexus"))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("split across 3 credentials")
                .hasMessageContaining("part 2 is missing");

        part2[0] ^= 1;
        calls.blobs.put("jk:repo/nexus#2", part2);
        assertThatThrownBy(() -> backend.read("repo/nexus")).hasMessageContaining("do not match their head");

        calls.blobs.put("jk:repo/nexus", new byte[] {WindowsCredentialBackend.HEAD_MARK, 'x'});
        assertThatThrownBy(() -> backend.read("repo/nexus")).hasMessageContaining("head is unreadable");
        backend.delete("repo/nexus");
        assertThat(calls.blobs).isEmpty();
    }

    @Test
    void a_delete_that_stops_partway_is_finished_by_the_next() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        backend.write("repo/nexus", "l", token(10_000));
        calls.refuseDeletes.add("jk:repo/nexus#2");

        assertThatThrownBy(() -> backend.delete("repo/nexus")).hasMessageContaining("refused to remove repo/nexus");
        assertThatThrownBy(() -> backend.read("repo/nexus")).isInstanceOf(SecretBackendException.class);

        calls.refuseDeletes.clear();
        backend.delete("repo/nexus");
        assertThat(calls.blobs).isEmpty();
    }

    @Test
    void parts_left_by_an_earlier_failure_are_swept_by_the_next_write_or_delete() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        for (int part = 1; part <= 5; part++) calls.blobs.put("jk:repo/nexus#" + part, new byte[] {'x'});

        backend.write("repo/nexus", "l", token(6000));
        assertThat(calls.blobs)
                .containsOnlyKeys("jk:repo/nexus", "jk:repo/nexus#1", "jk:repo/nexus#2", "jk:repo/nexus#3");

        for (int part = 4; part <= 5; part++) calls.blobs.put("jk:repo/nexus#" + part, new byte[] {'x'});
        backend.write("repo/nexus", "l", "short");
        assertThat(calls.blobs).containsOnlyKeys("jk:repo/nexus");
    }

    @Test
    void a_secret_past_every_part_is_refused_before_any_call() {
        FakeCalls calls = new FakeCalls();
        WindowsCredentialBackend backend = new WindowsCredentialBackend(calls);
        int most = WindowsCredentialBackend.MAX_PARTS * WindowsCredentialBackend.MAX_BLOB_BYTES;

        backend.write("repo/nexus", "l", token(most));
        assertThat(backend.read("repo/nexus")).contains(token(most));
        calls.targets.clear();

        assertThatThrownBy(() -> backend.write("repo/other", "l", token(most + 1)))
                .isInstanceOf(SecretBackendException.class)
                .hasMessageContaining("at most 32 credentials of 2560 bytes")
                .hasMessageContaining((most + 1) + " bytes in UTF-8");
        assertThat(calls.targets).isEmpty();
    }

    /** A JWT-shaped ASCII token of {@code length} characters, the same for the same length. */
    private static String token(int length) {
        StringBuilder sb = new StringBuilder("eyJ");
        for (int i = 0; sb.length() < length; i++) sb.append((char) ('a' + i % 26));
        return sb.substring(0, length);
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
