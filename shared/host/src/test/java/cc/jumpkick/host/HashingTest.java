// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashingTest {

    /** The published SHA-256 of the empty input — an independent value, not one jk computed. */
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** The published SHA-256 of "abc". */
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void sha256Hex_matches_the_published_vectors() {
        assertThat(Hashing.sha256Hex(new byte[0])).isEqualTo(EMPTY_SHA256);
        assertThat(Hashing.sha256Hex("abc")).isEqualTo(ABC_SHA256);
        assertThat(Hashing.hex(Hashing.sha256("abc".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(ABC_SHA256);
    }

    @Test
    void sha256Hex_of_a_file_matches_the_same_bytes_in_memory(@TempDir Path dir) throws IOException {
        // Larger than the streaming buffer, so the multi-read path is the one under test.
        byte[] big = new byte[64 * 1024 * 3 + 17];
        new Random(42).nextBytes(big);
        Path file = Files.write(dir.resolve("blob.bin"), big);
        assertThat(Hashing.sha256Hex(file)).isEqualTo(Hashing.sha256Hex(big));
        assertThat(Hashing.fileHex("SHA-256", file)).isEqualTo(Hashing.sha256Hex(big));
    }

    @Test
    void fileHex_hashes_under_the_algorithm_a_foreign_format_asks_for(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "abc");
        // Published SHA-1 of "abc" — what a Maven .sha1 sidecar would advertise.
        assertThat(Hashing.fileHex("SHA-1", file)).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
        assertThat(Hashing.hashHex("SHA-1", "abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    @Test
    void hex_is_lowercase() {
        assertThat(Hashing.hex(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}))
                .isEqualTo("deadbeef");
        // The mask a hand-rolled loop forgets: a negative byte is still two hex digits.
        assertThat(Hashing.hex(new byte[] {(byte) 0x0f, (byte) 0xf0})).isEqualTo("0ff0");
    }

    @Test
    void isHex_accepts_either_case_and_rejects_everything_else() {
        assertThat(Hashing.isHex("00ff")).isTrue();
        assertThat(Hashing.isHex("00FF")).isTrue();
        assertThat(Hashing.isHex("00g0")).isFalse();
        assertThat(Hashing.isHex("")).isFalse();
        assertThat(Hashing.isHex(null)).isFalse();
        assertThat(Hashing.isHex("dead", 4)).isTrue();
        assertThat(Hashing.isHex("dead", 5)).isFalse();
        assertThat(Hashing.isHex(null, 4)).isFalse();
    }

    @Test
    void checksumFromSidecar_takes_the_first_token_of_either_sidecar_shape() {
        String sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
        // Bare digest, and the sha1sum `<hex>  <filename>` form both repositories publish.
        assertThat(Hashing.checksumFromSidecar(sha1, 40)).contains(sha1);
        assertThat(Hashing.checksumFromSidecar("  " + sha1 + "\t\n", 40)).contains(sha1);
        assertThat(Hashing.checksumFromSidecar(sha1 + "  widget-1.0.jar\n", 40)).contains(sha1);
        // Uppercase in, lowercase out — comparisons downstream are then plain equals.
        assertThat(Hashing.checksumFromSidecar(sha1.toUpperCase(Locale.ROOT), 40))
                .contains(sha1);
    }

    @Test
    void checksumFromSidecar_rejects_a_body_that_is_not_a_digest_of_that_width() {
        String sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
        // A repository that answers a missing sidecar with an HTML error page under HTTP 200.
        assertThat(Hashing.checksumFromSidecar("<html><body>404</body></html>", 40))
                .isEmpty();
        assertThat(Hashing.checksumFromSidecar("", 40)).isEmpty();
        assertThat(Hashing.checksumFromSidecar(null, 40)).isEmpty();
        // Right shape, wrong width: a SHA-1 body where a SHA-256 sidecar was asked for.
        assertThat(Hashing.checksumFromSidecar(sha1, 64)).isEmpty();
    }
}
