// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key a remote cache's clients key environment values under in a suite key's shared form
 * ({@code SuiteEnvReads.sharedTokens}), derived from the cache's seed ({@link CacheSeeds}): the seed
 * the cache issued or the one the team set, never this home's secret. Two hosts that hold the same
 * seed for the same cache key a value alike, so one replays the other's result; any other host,
 * and any other cache, keys it differently.
 *
 * <p>The derivation separates its domain twice: the HMAC key is not the seed but {@code
 * HMAC-SHA256(seed, "jk cache key seed 1\0" + scope)}, so a seed is never a key a home's secret
 * could be, and {@code scope} names the cache ({@code team\0<cache>} or {@code
 * issued\0<cache>\0<seed id>}), so one team seed set for two caches gives two unrelated keys. With
 * no remote cache the team's seed keys the request's own action cache ({@link SeedFormStore}) under
 * a fixed scope, {@code team\0local cache}, alike on every machine.
 *
 * <p>No key names the seed: a key's shared form changes with it because every value under it
 * does, so a new seed moves the shared keys of results that read a value and nothing else. Neither
 * the seed nor this key is ever printed: {@link #toString} names neither.
 */
public final class KeySeed {

    /** The label the key is derived under, versioned: a new derivation is a new label. */
    static final String LABEL = "jk cache key seed 1\u0000";

    private final byte[] key;

    private KeySeed(byte[] key) {
        this.key = key;
    }

    /** The key {@code seed} gives for {@code scope}. */
    public static KeySeed derive(byte[] seed, String scope) {
        return new KeySeed(mac(seed, LABEL + scope));
    }

    /** The HMAC-SHA256 of {@code text} under this key, hex. */
    public String hmac(String text) {
        return Hashing.hex(mac(key, text));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KeySeed other && Arrays.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "KeySeed[withheld]";
    }

    private static byte[] mac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }
}
