// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.Hashing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Masks {@code.env}-sourced values in text that leaves the process.
 *
 * <p>Masking is by <em>source</em>, not by name heuristics ({@code *_TOKEN}, {@code *_PASSWORD},
 * …): any value whose effective resolution came from a {@code.env} file is secret. That is what
 * {@link EnvLookup#isFromFile} records, and what this redactor consumes.
 *
 * <p>Two surfaces share one instinct:
 *
 * <ul>
 * <li>{@link #redact(String)} — replace secret substrings in free-form text (JSONL, journal,
 * error messages, labels, test output).
 * <li>{@link #forCacheKey(String)} — when a secret must participate in an action key, emit a
 * digest rather than the value. Matches how {@code VariantApply} secrets are folded into
 * package keys.
 * </ul>
 */
public final class SecretRedactor {

    /** What a secret becomes in free-form text. */
    public static final String MASK = "***";

    /**
     * Values shorter than this are treated as configuration, not credentials. Masking is
     * by source, so without a floor a {@code.env} holding {@code NODE_ENV=test} or
     * {@code PORT=8080} turns every {@code test} / {@code 8080} in build output into {@code ***}
     * and hashes unrelated cache-key text that contains the substring. Real tokens are comfortably
     * longer; a deliberately short secret is outside what source-based masking can protect.
     */
    public static final int MIN_SECRET_LENGTH = 6;

    /** Prefix for hashed cache-key contributions. */
    public static final String KEY_PREFIX = "sha256:";

    private static final SecretRedactor NONE = new SecretRedactor(List.of());

    /** Longest first so a value that is a prefix of another never leaves a residue. */
    private final List<String> secrets;

    private SecretRedactor(List<String> secrets) {
        this.secrets = List.copyOf(secrets);
    }

    /** No secrets — every string is left alone. */
    public static SecretRedactor none() {
        return NONE;
    }

    /**
     * Mask a trailing fragment of {@code text} that is a leading prefix (≥ {@link
     * #MIN_SECRET_LENGTH} chars, shorter than the whole value) of any secret. Capture-time
     * truncation can cut mid-value; the surviving prefix no longer matches the
     * exact-substring pass in {@link #redact}, so the seam is masked separately by callers that
     * know where the cut landed.
     */
    public String maskTrailingSecretPrefix(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        for (String secret : secrets) {
            int max = Math.min(secret.length() - 1, text.length());
            for (int len = max; len >= MIN_SECRET_LENGTH; len--) {
                if (text.regionMatches(text.length() - len, secret, 0, len)) {
                    return text.substring(0, text.length() - len) + MASK;
                }
            }
        }
        return text;
    }

    /**
     * Build a redactor from an {@link EnvLookup}: every effective value that came from a
     * {@code.env} file (not the real environment).
     */
    /** Redactors are immutable; memo by value-set so per-line redaction reuses one. */
    private static final ConcurrentHashMap<Set<String>, SecretRedactor> MEMO = new ConcurrentHashMap<>();

    public static SecretRedactor from(EnvLookup env) {
        Objects.requireNonNull(env, "env");
        Set<String> values = new LinkedHashSet<>();
        for (String name : env.fileNames()) {
            if (!env.isFromFile(name)) continue; // real env won — not a file secret
            String v = env.get(name);
            if (v != null && !v.isEmpty()) values.add(v);
        }
        if (values.isEmpty()) return NONE;
        if (MEMO.size() > 64) MEMO.clear(); // a handful of.env sets per engine; crude bound is fine
        return MEMO.computeIfAbsent(Set.copyOf(values), SecretRedactor::of);
    }

    /** Build a redactor from an explicit set of secret values (tests, side-channel secrets). */
    public static SecretRedactor of(Collection<String> values) {
        if (values == null || values.isEmpty()) return NONE;
        List<String> list = new ArrayList<>();
        for (String v : values) {
            if (v != null && v.length() >= MIN_SECRET_LENGTH) list.add(v);
        }
        if (list.isEmpty()) return NONE;
        // Longest first: replacing a shorter substring first can leave pieces of a longer secret.
        list.sort(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
        return new SecretRedactor(list);
    }

    /** True when this redactor has nothing to mask. */
    public boolean isEmpty() {
        return secrets.isEmpty();
    }

    /** True when {@code text} contains at least one known secret substring. */
    public boolean containsSecret(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return false;
        for (String s : secrets) {
            if (text.contains(s)) return true;
        }
        return false;
    }

    /**
     * Replace every occurrence of a known secret in {@code text} with {@link #MASK}. Null stays
     * null; when there are no secrets the input is returned unchanged.
     */
    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) return text;
        String out = text;
        for (String s : secrets) {
            if (out.contains(s)) out = out.replace(s, MASK);
        }
        return out;
    }

    /**
     * A redactor that additionally matches each secret's JSON-string-escaped rendering.
     *
     * <p>Replay paths re-redact <em>escaped JSON documents</em>: a secret containing {@code "},
     * {@code \}, or a control character was persisted through {@code Jsonl.quote} in escaped form,
     * which the exact-substring pass over the raw document cannot match. This view carries both
     * renderings so escaped occurrences are masked without decode–re-encode. Memoized per
     * instance; returns {@code this} when no secret changes under escaping.
     */
    public SecretRedactor forEscapedJson() {
        if (secrets.isEmpty()) return this;
        SecretRedactor v = escapedJsonView;
        if (v == null) {
            List<String> all = new ArrayList<>(secrets);
            for (String s : secrets) {
                String esc = jsonEscape(s);
                if (!esc.equals(s)) all.add(esc);
            }
            v = all.size() == secrets.size() ? this : of(all);
            escapedJsonView = v;
        }
        return v;
    }

    private volatile SecretRedactor escapedJsonView;

    /**
     * JSON string-body escaping, delegated to {@code Jsonl.quote} (the writer this redaction
     * must stay in lock-step with) minus its surrounding quotes — the module is a direct
     * dependency now, so the old hand-copied twin is gone (JK-2172).
     */
    private static String jsonEscape(String s) {
        String quoted = cc.jumpkick.jsonl.Jsonl.quote(s);
        return quoted.substring(1, quoted.length() - 1);
    }

    /**
     * Form suitable for an action / stamp key. If {@code value} is itself a secret or contains one,
     * return {@code sha256:<hex>} of the full value; otherwise return {@code value} unchanged.
     *
     * <p>Hashing the whole value (not only the secret substring) keeps the key stable when a secret
     * is embedded in a larger string, and never leaves a literal token on disk or in a shared
     * cache key.
     */
    public String forCacheKey(String value) {
        if (value == null) return null;
        if (secrets.isEmpty() || !containsSecret(value)) return value;
        return KEY_PREFIX + Hashing.sha256Hex(value);
    }
}
