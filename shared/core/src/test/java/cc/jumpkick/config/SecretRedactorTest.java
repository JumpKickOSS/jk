// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code.env}-sourced values are secret by source, not by name. The redactor masks them
 * in free-form text and hashes them for cache keys.
 */
class SecretRedactorTest {

    @Test
    void redacts_file_sourced_values_and_leaves_real_env_alone(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "TOKEN=s3cret-from-file\nMODE=file\n");
        EnvLookup env = EnvLookup.forModule(module, name -> "MODE".equals(name) ? "from-shell" : null);

        SecretRedactor r = SecretRedactor.from(env);

        // TOKEN is file-sourced → secret.
        assertThat(r.redact("Authorization: Bearer s3cret-from-file"))
                .isEqualTo("Authorization: Bearer " + SecretRedactor.MASK);
        // MODE is shadowed by the real env → not a secret (even though.env also names it).
        assertThat(r.redact("mode=from-shell")).isEqualTo("mode=from-shell");
        assertThat(r.redact("mode=file")).isEqualTo("mode=file");
    }

    @Test
    void longer_secrets_are_replaced_before_shorter_prefixes() {
        SecretRedactor r = SecretRedactor.of(List.of("abcdef", "abcdefghij"));
        assertThat(r.redact("x-abcdefghij-y")).isEqualTo("x-" + SecretRedactor.MASK + "-y");
    }

    @Test
    void a_dangling_secret_prefix_at_a_truncation_cut_is_masked() {
        // JK-1960: capture-time truncation can cut mid-value; the surviving prefix no longer
        // matches the exact-substring pass and must be masked at the seam by the caller.
        SecretRedactor r = SecretRedactor.of(List.of("s3cret-token-value"));
        assertThat(r.maskTrailingSecretPrefix("Bearer s3cret-tok")).isEqualTo("Bearer " + SecretRedactor.MASK);
        // Below the floor, or unrelated tails, stay untouched.
        assertThat(r.maskTrailingSecretPrefix("Bearer s3cr")).isEqualTo("Bearer s3cr");
        assertThat(r.maskTrailingSecretPrefix("nothing here")).isEqualTo("nothing here");
        // A full occurrence is redact()'s job, not the seam's — the whole value at the tail is
        // still caught here (prefix length capped below the full value keeps them disjoint).
        assertThat(r.maskTrailingSecretPrefix("x s3cret-token-value")).isEqualTo("x s3cret-token-value");
    }

    @Test
    void short_common_values_are_config_not_credentials() {
        // NODE_ENV=test / PORT=8080 style.env entries must not mangle output.
        SecretRedactor r = SecretRedactor.of(List.of("test", "8080", "info"));
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.redact("running 12 tests on port 8080 at info level"))
                .isEqualTo("running 12 tests on port 8080 at info level");
        assertThat(r.forCacheKey("suite=test")).isEqualTo("suite=test");
    }

    @Test
    void the_length_floor_is_exact(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(
                module.resolve(".env"),
                "SHORT=" + "x".repeat(SecretRedactor.MIN_SECRET_LENGTH - 1) + "\n" + "LONG="
                        + "y".repeat(SecretRedactor.MIN_SECRET_LENGTH) + "\n");
        SecretRedactor r = SecretRedactor.from(EnvLookup.forModule(module, name -> null));

        assertThat(r.containsSecret("x".repeat(SecretRedactor.MIN_SECRET_LENGTH - 1)))
                .isFalse();
        assertThat(r.redact("y".repeat(SecretRedactor.MIN_SECRET_LENGTH))).isEqualTo(SecretRedactor.MASK);
    }

    @Test
    void cache_key_hashes_when_a_secret_is_present() {
        SecretRedactor r = SecretRedactor.of(List.of("s3cret"));
        String hashed = r.forCacheKey("s3cret");
        assertThat(hashed).startsWith(SecretRedactor.KEY_PREFIX);
        assertThat(hashed).doesNotContain("s3cret");
        // Embedded secret → hash the whole value.
        String composite = r.forCacheKey("prefix-s3cret-suffix");
        assertThat(composite).startsWith(SecretRedactor.KEY_PREFIX);
        assertThat(composite).doesNotContain("s3cret");
        // Non-secret stays literal.
        assertThat(r.forCacheKey("public")).isEqualTo("public");
    }

    @Test
    void escaped_json_view_masks_both_renderings() {
        // JK-1975: replay paths redact escaped JSON documents; a secret with a quote, backslash,
        // or newline was persisted in escaped form.
        SecretRedactor r = SecretRedactor.of(List.of("pa\\ss\"wd\n9"));
        SecretRedactor json = r.forEscapedJson();
        assertThat(json.redact("leak pa\\\\ss\\\"wd\\n9 here")).isEqualTo("leak *** here");
        // The raw rendering still masks too.
        assertThat(json.redact("leak pa\\ss\"wd\n9 here")).isEqualTo("leak *** here");
        // Control chars use the lowercase four-digit form (Jsonl.quote parity).
        SecretRedactor ctl = SecretRedactor.of(List.of("ab\u0001cdef"));
        assertThat(ctl.forEscapedJson().redact("x ab\\u0001cdef y")).isEqualTo("x *** y");
        // Secrets that escape to themselves reuse the same instance; the view is memoized.
        SecretRedactor plain = SecretRedactor.of(List.of("plain-secret"));
        assertThat(plain.forEscapedJson()).isSameAs(plain);
        assertThat(json).isSameAs(r.forEscapedJson());
    }

    @Test
    void none_is_a_no_op() {
        assertThat(SecretRedactor.none().redact("anything")).isEqualTo("anything");
        assertThat(SecretRedactor.none().forCacheKey("anything")).isEqualTo("anything");
        assertThat(SecretRedactor.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    void secretValues_matches_isFromFile(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "A=aaaaaa-token\nB=bbbbbb-token\n");
        EnvLookup env = EnvLookup.forModule(module, name -> "B".equals(name) ? "from-shell" : null);
        assertThat(SecretRedactor.from(env).containsSecret("aaaaaa-token")).isTrue();
        assertThat(SecretRedactor.from(env).containsSecret("bbbbbb-token")).isFalse();
    }
}
