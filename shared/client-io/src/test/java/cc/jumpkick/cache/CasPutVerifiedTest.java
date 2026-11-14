// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A blob that arrives from another machine enters the CAS only under the digest its bytes hash to:
 * bytes sent under another digest are refused and leave nothing behind, neither a blob nor a temp.
 */
class CasPutVerifiedTest {

    private static final byte[] BODY = "the bytes that were asked for".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "the bytes that came instead".getBytes(StandardCharsets.UTF_8);

    @Test
    void bytes_that_hash_to_the_asked_digest_are_stored_under_it(@TempDir Path tmp) throws IOException {
        Cas cas = new Cas(tmp.resolve("cas"));
        String sha = Hashing.sha256Hex(BODY);

        Cas.Stored stored = cas.putVerified(new ByteArrayInputStream(BODY), sha.toUpperCase(Locale.ROOT));

        assertThat(stored.sha256()).isEqualTo(sha);
        assertThat(stored.size()).isEqualTo(BODY.length);
        assertThat(cas.read(sha)).isEqualTo(BODY);
    }

    @Test
    void bytes_that_hash_elsewhere_are_refused_and_leave_nothing(@TempDir Path tmp) throws IOException {
        Cas cas = new Cas(tmp.resolve("cas"));
        String asked = Hashing.sha256Hex(BODY);

        assertThatThrownBy(() -> cas.putVerified(new ByteArrayInputStream(OTHER), asked))
                .isInstanceOf(Cas.DigestMismatch.class)
                .hasMessageContaining(asked)
                .hasMessageContaining(Hashing.sha256Hex(OTHER));

        assertThat(cas.contains(asked)).isFalse();
        assertThat(cas.contains(Hashing.sha256Hex(OTHER))).isFalse();
        assertThat(files(tmp.resolve("cas"))).as("no blob and no staging temp").isEmpty();
    }

    @Test
    void a_digest_that_is_not_a_sha256_is_refused_before_any_byte_is_read(@TempDir Path tmp) {
        Cas cas = new Cas(tmp.resolve("cas"));
        assertThatThrownBy(() -> cas.putVerified(new ByteArrayInputStream(BODY), "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
