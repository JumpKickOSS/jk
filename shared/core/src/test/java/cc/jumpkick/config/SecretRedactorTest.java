// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A name a {@code.env} declares is a secret name, and its effective value is secret whichever
 * layer supplied it. The redactor masks those values in free-form text and hashes them for cache
 * keys. Names no {@code.env} mentions are left alone — nothing can enumerate the host
 * environment, and guessing by name is the heuristic this class exists to avoid.
 */
class SecretRedactorTest {

    @Test
    void redacts_declared_names_and_leaves_undeclared_env_alone(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "TOKEN=s3cret-from-file\nMODE=file-value\n");
        EnvLookup env = EnvLookup.forModule(
                module, name -> Map.of("MODE", "from-shell", "PATH", "/usr/bin").get(name));

        SecretRedactor r = SecretRedactor.from(env);

        // TOKEN is declared and unshadowed → secret.
        assertThat(r.redact("Authorization: Bearer s3cret-from-file"))
                .isEqualTo("Authorization: Bearer " + SecretRedactor.MASK);
        // MODE is declared and shadowed. Its EFFECTIVE value is the shell's, and that is the one
        // that reaches output — so that is the one masked. This used to be the other way round,
        // which meant a `.env`-declared token overridden by CI (the normal shape) was the single
        // value the redactor never touched.
        assertThat(r.redact("mode=from-shell")).isEqualTo("mode=" + SecretRedactor.MASK);
        // The losing file value is not masked: nothing resolves to it, so nothing prints it.
        assertThat(r.redact("mode=file-value")).isEqualTo("mode=file-value");
        // PATH is real-environment only. No `.env` names it, so it is not a secret.
        assertThat(r.redact("PATH=/usr/bin")).isEqualTo("PATH=/usr/bin");
    }

    @Test
    void longer_secrets_are_replaced_before_shorter_prefixes() {
        SecretRedactor r = SecretRedactor.of(List.of("abcdef", "abcdefghij"));
        assertThat(r.redact("x-abcdefghij-y")).isEqualTo("x-" + SecretRedactor.MASK + "-y");
    }

    @Test
    void a_dangling_secret_prefix_at_a_truncation_cut_is_masked() {
        // capture-time truncation can cut mid-value; the surviving prefix no longer
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
        // replay paths redact escaped JSON documents; a secret with a quote, backslash,
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
        assertThat(SecretRedactor.from(env).containsSecret("bbbbbb-token"))
                .as("the file value B lost the precedence race, so it never reaches output")
                .isFalse();
    }

    /**
     * A shadowed name is still a secret name. {@code.env} supplies the default and CI exports the
     * real one, so the value that actually reaches the wire is the environment's — and under the
     * old {@code isFromFile} rule that was the one value never masked. The degenerate case is
     * worse: the same literal token in both places went unmasked purely because the shell also
     * exported it.
     */
    @Test
    void a_shadowed_env_name_still_names_a_secret(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "NEXUS_TOKEN=dev-default-token\nNODE_ENV=development\n");
        EnvLookup env = EnvLookup.forModule(module, name -> "NEXUS_TOKEN".equals(name) ? "ci-real-token-9f3a" : null);

        assertThat(env.isFromFile("NEXUS_TOKEN"))
                .as("precedence: the real environment won")
                .isFalse();

        SecretRedactor r = SecretRedactor.from(env);
        assertThat(r.redact("GET https://nexus/ failed: Bearer ci-real-token-9f3a rejected"))
                .isEqualTo("GET https://nexus/ failed: Bearer *** rejected");
        assertThat(r.forCacheKey("ci-real-token-9f3a")).startsWith(SecretRedactor.KEY_PREFIX);
        assertThat(r.containsSecret("development"))
                .as("an unshadowed name is admitted exactly as before")
                .isTrue();
    }

    /** The same string in both layers: the reason precedence cannot be the secrecy predicate. */
    @Test
    void the_same_token_in_both_layers_is_masked(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("m");
        Files.createDirectories(module);
        Files.writeString(module.resolve(".env"), "GITHUB_TOKEN=ghp_same-token-both\n");
        EnvLookup env = EnvLookup.forModule(module, name -> "GITHUB_TOKEN".equals(name) ? "ghp_same-token-both" : null);

        assertThat(SecretRedactor.from(env).redact("auth: ghp_same-token-both")).isEqualTo("auth: ***");
    }
}
