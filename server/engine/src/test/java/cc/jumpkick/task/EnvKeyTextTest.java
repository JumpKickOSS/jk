// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A value of the caller's environment enters a step key sealed: no plain digest of it anywhere, an
 * HMAC under the home's secret that keys two homes apart, and a shared form under the cache key seed
 * that keys two homes with one seed alike, through any digest nested inside the key. A key holding
 * a value with no shared form (no seed, a credential kept here) is noted as its own and leaves
 * nowhere. The seeds, secrets and values are made up.
 */
class EnvKeyTextTest {

    private static final String VALUE = "made-up-signing-password-42";

    private static final EnvReadKey HOME_A = home(1);
    private static final EnvReadKey HOME_B = home(2);

    private static final CacheSeeds.Team TEAM =
            new CacheSeeds.Team("made-up-team-seed-for-env-keys".getBytes(StandardCharsets.UTF_8));

    @Test
    void a_sealed_value_holds_no_plain_digest_of_it() {
        String sealed = new EnvKeyText.Sealer(null, HOME_A).seal(VALUE, false);

        assertThat(sealed).startsWith(EnvKeyText.SEALED).doesNotContain(VALUE);
        assertNoPlainDigest(sealed, VALUE);
        assertThat(new EnvKeyText.Sealer(null, HOME_B).seal(VALUE, false))
                .as("another home's secret seals the value apart")
                .isNotEqualTo(sealed);
    }

    @Test
    void two_homes_with_one_seed_share_a_key_and_its_nested_digest_and_another_value_does_not() throws Exception {
        Keys a = keys(seeded(false), HOME_A, VALUE);
        Keys b = keys(seeded(false), HOME_B, VALUE);
        Keys other = keys(seeded(false), HOME_B, "another-made-up-password");

        assertThat(a.local).as("two homes key apart here").isNotEqualTo(b.local);
        assertThat(a.shared).isNotNull().isNotEqualTo(a.local).isEqualTo(b.shared);
        assertThat(a.outerShared)
                .as("a key over a digest that held the value shares through the nesting")
                .isNotNull()
                .isEqualTo(b.outerShared);
        assertThat(other.shared).as("another value").isNotEqualTo(a.shared);
        assertNoPlainDigest(a.text, VALUE);
    }

    /**
     * A plugin step's and a packager's key is {@link ActionKey#forArtifact} over their tokens, the
     * module's {@code plugin-env:} among them: two homes with one seed and one value note one shared
     * key, another value another, and the key holds no plain digest of the value.
     */
    @Test
    void a_plugin_steps_key_shares_under_one_seed_across_homes() throws Exception {
        String a = pluginKey(seeded(false), HOME_A, VALUE);
        String b = pluginKey(seeded(false), HOME_B, VALUE);
        String other = pluginKey(seeded(false), HOME_B, "another-made-up-password");

        assertThat(a).isNotNull().isEqualTo(b).isNotEqualTo(other);
    }

    /** The shared form {@code tier} notes for a plugin step's key over a {@code plugin-env:} of {@code value}. */
    private static @Nullable String pluginKey(RemoteTier tier, EnvReadKey home, String value) throws Exception {
        return RemoteCaches.where(tier, () -> {
            List<String> tokens = List.of(
                    "facts:com.example:m:1.0.0|release=25",
                    "plugin-env:SIGNING_PASSWORD=" + new EnvKeyText.Sealer(tier, home).seal(value, false),
                    "worker:" + Hashing.sha256Hex("made-up worker jar"));
            String key = ActionKey.forArtifact("plugin-sign@abc", "1", tokens);
            assertNoPlainDigest(String.join("\n", tokens), value);
            return tier.sharedForm(key);
        });
    }

    @Test
    void without_a_seed_two_homes_keep_apart_and_the_key_is_its_own_shared_form() throws Exception {
        RemoteTier tier = unseeded();
        Keys a = keys(tier, HOME_A, VALUE);
        Keys b = keys(unseeded(), HOME_B, VALUE);

        assertThat(a.local).isNotEqualTo(b.local);
        assertThat(tier.noted(a.local))
                .as("noted as its own: it is uploaded nowhere")
                .isTrue();
        assertThat(a.shared).isNull();
        assertThat(a.outerShared).isNull();
        assertThat(tier.noted(a.outer)).isTrue();
    }

    @Test
    void a_credential_stays_here_when_the_user_keeps_credentials_local() throws Exception {
        RemoteTier tier = seeded(true);
        EnvKeyText.Sealer sealer = new EnvKeyText.Sealer(tier, HOME_A);
        String credential = RemoteCaches.where(tier, () -> EnvKeyText.digest("env:PW=" + sealer.seal(VALUE, true)));
        String plain = RemoteCaches.where(tier, () -> EnvKeyText.digest("env:MODE=" + sealer.seal("fast", false)));

        assertThat(tier.noted(credential)).isTrue();
        assertThat(tier.sharedForm(credential)).isNull();
        assertThat(tier.sharedForm(plain))
                .as("a value that is no credential still shares")
                .isNotNull();
    }

    @Test
    void a_key_that_holds_no_sealed_value_is_not_noted() throws Exception {
        RemoteTier tier = seeded(false);
        new EnvKeyText.Sealer(tier, HOME_A).seal(VALUE, false);
        String key =
                RemoteCaches.where(tier, () -> EnvKeyText.digest("task:x\nsrc:" + Hashing.sha256Hex("class A {}")));

        assertThat(key).isEqualTo(Hashing.sha256Hex("task:x\nsrc:" + Hashing.sha256Hex("class A {}")));
        assertThat(tier.noted(key)).isFalse();
    }

    @Test
    void tokens_another_hash_takes_are_given_in_their_shared_form() throws Exception {
        RemoteTier a = seeded(false);
        RemoteTier b = seeded(false);
        List<String> tokensA = List.of("x:y", "inherited-env:" + new EnvKeyText.Sealer(a, HOME_A).seal(VALUE, false));
        List<String> tokensB = List.of("x:y", "inherited-env:" + new EnvKeyText.Sealer(b, HOME_B).seal(VALUE, false));
        List<String> plain = List.of("x:y");

        List<String> sharedA = RemoteCaches.where(a, () -> EnvKeyText.sharedTokens(tokensA));
        assertThat(sharedA)
                .isNotEqualTo(tokensA)
                .isEqualTo(RemoteCaches.where(b, () -> EnvKeyText.sharedTokens(tokensB)));
        assertThat(RemoteCaches.where(a, () -> EnvKeyText.sharedTokens(plain))).isSameAs(plain);

        RemoteTier none = unseeded();
        List<String> hostOnly = List.of("inherited-env:" + new EnvKeyText.Sealer(none, HOME_A).seal(VALUE, false));
        assertThat(RemoteCaches.where(none, () -> EnvKeyText.sharedTokens(hostOnly)))
                .isNull();
    }

    /** A step key over one sealed value, and a key over a digest of a text that held it. */
    private record Keys(
            String text,
            String local,
            @Nullable String shared,
            String outer,
            @Nullable String outerShared) {}

    private static Keys keys(RemoteTier tier, EnvReadKey home, String value) throws Exception {
        return RemoteCaches.where(tier, () -> {
            String text = "task:plugin-sign@abc\njk:1\nplugin-env:SIGNING_PASSWORD="
                    + new EnvKeyText.Sealer(tier, home).seal(value, false) + "\n";
            String local = EnvKeyText.digest(text);
            String outer = EnvKeyText.digest(
                    "task:ksp-round@abc\noptions:" + local + "\nsource:A.kt:" + Hashing.sha256Hex("fun a() = 1"));
            return new Keys(text, local, tier.sharedForm(local), outer, tier.sharedForm(outer));
        });
    }

    private static RemoteTier seeded(boolean credentialsLocal) {
        return new RemoteTier(new MemoryCacheBackend(), "linux-x86_64", true).seededBy(TEAM, credentialsLocal);
    }

    private static RemoteTier unseeded() {
        return new RemoteTier(new MemoryCacheBackend(), "linux-x86_64", true).seededBy(CacheSeeds.Source.OFF, false);
    }

    private static EnvReadKey home(int fill) {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) fill);
        return EnvReadKey.of(secret);
    }

    private static void assertNoPlainDigest(String text, String value) {
        PlainDigests.assertNone(text, value);
    }
}
