// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.Hashing;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a value of the caller's environment enters a step key, and how a key that holds one is
 * hashed. A value enters sealed ({@link Sealer#seal}): an HMAC under this jk home's secret ({@link
 * EnvReadKey}), never a plain digest, so neither a key that leaves the host nor a memo in the project
 * directory gives a way to test guesses at a short value. With a remote tier bound, the same value
 * under the cache key seed ({@link KeySeed}) is noted as the part's shared form ({@link
 * RemoteTier#shareAs}), or the part as its own shared form when no seed is in force or the value is a
 * credential the user keeps on the host ({@code credential-env = "local"}).
 *
 * <p>{@link #digest} is the hash of every key text such a part can reach. It returns the plain
 * SHA-256 of the text, the key this host takes, and when the text holds a noted part it takes the
 * text again with each part in its shared form and notes that digest as the key's own shared form.
 * The shared form of a key is so the key over the shared forms of its parts, through any nesting (an
 * options digest inside a round key): a host with the same seed and the same values derives the same
 * shared key, any other host another. A key holding a part with no shared form is noted as its own,
 * and its record is uploaded nowhere. A key derived through a hash that is not this one still holds
 * only the sealed part: it loses its shared form, never its secrecy.
 *
 * <p>Values a manifest writes literally are constants of the checkout, not the caller's, and key by
 * {@link #literal}.
 */
public final class EnvKeyText {

    private EnvKeyText() {}

    /** The spelling of a sealed value in a key: the HMAC follows it in hex. */
    public static final String SEALED = "hmac:";

    /** The domain a step key's values are sealed under, versioned: a new derivation is a new label. */
    static final String LABEL = "jk step env 1\u0000";

    private static final int HEX = 64;

    /**
     * A sealer for one key derivation: this home's secret, read once when a value first needs it,
     * and the build's remote tier, if any, with its seed.
     */
    public static Sealer sealer() {
        return new Sealer(RemoteCaches.current());
    }

    /** Whether {@code name}, a variable a value was read through, reads as a credential. */
    public static boolean credential(String name) {
        return BuildEnv.looksSecret(name) || ChildEnv.credential(name);
    }

    /**
     * The digest of {@code value}, a constant the manifest writes literally (a profile's or a suite's
     * {@code env} table): keyed by plain digest, as it is the checkout's and no secret of the caller.
     */
    public static String literal(String value) {
        return Hashing.sha256Hex(value);
    }

    /** One derivation's sealing of values. */
    public static final class Sealer {
        private final @Nullable RemoteTier tier;
        private @Nullable EnvReadKey home;

        private Sealer(@Nullable RemoteTier tier) {
            this(tier, null);
        }

        /** A sealer under {@code home} (another home's secret, for a test), or this home's when null. */
        Sealer(@Nullable RemoteTier tier, @Nullable EnvReadKey home) {
            this.tier = tier;
            this.home = home;
        }

        /**
         * {@code value} as a key holds it: {@link #SEALED} and its HMAC under this home's secret, its
         * shared form noted on the build's tier. {@code credential} says the value was read through a
         * name that reads as a credential ({@link EnvKeyText#credential}).
         */
        public String seal(String value, boolean credential) {
            EnvReadKey k = home;
            if (k == null) home = k = EnvReadKey.load();
            String local = k.hmac(LABEL + value);
            RemoteTier t = tier;
            if (t != null) {
                KeySeed seed = credential && t.credentialsLocal() ? null : t.keySeed();
                t.notePart(local, seed == null ? local : seed.hmac(LABEL + value));
            }
            return SEALED + local;
        }
    }

    /**
     * The SHA-256 of {@code text}, a key or a part of one, hex: with its shared form noted on the
     * build's tier when the text holds a part the tier noted ({@link RemoteTier#notePart}).
     */
    public static String digest(CharSequence text) {
        String local = Hashing.sha256Hex(text.toString());
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || !tier.holdsParts()) return local;
        String shared = shared(text, tier);
        if (shared != null) tier.notePart(local, shared.equals(HOST_ONLY) ? local : Hashing.sha256Hex(shared));
        return local;
    }

    /**
     * {@code tokens}, key tokens a hash other than {@link #digest} takes (a run-tests stamp's
     * extras), with each part the build's tier noted in its shared form: {@code tokens} itself when
     * none holds one, null when one has no shared form.
     */
    public static @Nullable List<String> sharedTokens(List<String> tokens) {
        RemoteTier tier = RemoteCaches.current();
        if (tier == null || !tier.holdsParts()) return tokens;
        List<String> out = null;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String shared = shared(token, tier);
            if (HOST_ONLY.equals(shared)) return null;
            if (shared != null && out == null) out = new ArrayList<>(tokens.subList(0, i));
            if (out != null) out.add(shared == null ? token : shared);
        }
        return out == null ? tokens : out;
    }

    /** What {@link #shared} answers for a text holding a part with no shared form. */
    private static final String HOST_ONLY = "\u0000host-only";

    /**
     * {@code text} with each part {@code tier} noted in its shared form; {@link #HOST_ONLY} when one
     * has none, or null when the text holds no noted part.
     */
    static @Nullable String shared(CharSequence text, RemoteTier tier) {
        StringBuilder out = null;
        int copied = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!hex(text.charAt(i)) || (i > 0 && hex(text.charAt(i - 1)))) {
                i++;
                continue;
            }
            int end = i;
            while (end < n && hex(text.charAt(end))) end++;
            if (end - i == HEX) {
                String part = text.subSequence(i, end).toString();
                String form = tier.formOf(part);
                if (form != null) {
                    if (form.equals(part)) return HOST_ONLY;
                    if (out == null) out = new StringBuilder(n);
                    out.append(text, copied, i).append(form);
                    copied = end;
                }
            }
            i = end;
        }
        if (out == null) return null;
        return out.append(text, copied, n).toString();
    }

    private static boolean hex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }
}
