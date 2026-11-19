// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/** The spellings a plain SHA-256 of an environment value could take in a key, a record or a memo. */
public final class PlainDigests {

    private PlainDigests() {}

    /** {@code text} holds the SHA-256 of {@code value} in none of them: hex either case, its 16-digit prefix, base64. */
    public static void assertNone(String text, String value) {
        for (String spelling : of(value))
            assertThat(text).as("a plain digest of a value").doesNotContain(spelling);
    }

    /** Every spelling of the SHA-256 of {@code value} a search should look for. */
    public static String[] of(String value) {
        byte[] digest = Hashing.sha256(value.getBytes(StandardCharsets.UTF_8));
        return new String[] {
            HexFormat.of().formatHex(digest),
            HexFormat.of().withUpperCase().formatHex(digest),
            HexFormat.of().formatHex(digest).substring(0, 16),
            Base64.getEncoder().encodeToString(digest),
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        };
    }
}
