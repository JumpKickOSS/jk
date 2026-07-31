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

/**
 * Masks {@code .env}-sourced values in text that leaves the process (JK-1274).
 *
 * <p>Masking is by <em>source</em>, not by name heuristics ({@code *_TOKEN}, {@code *_PASSWORD},
 * …): any value whose effective resolution came from a {@code .env} file is secret. That is what
 * {@link EnvLookup#isFromFile} records, and what this redactor consumes.
 *
 * <p>Two surfaces share one instinct:
 *
 * <ul>
 *   <li>{@link #redact(String)} — replace secret substrings in free-form text (JSONL, journal,
 *       error messages, labels, test output).
 *   <li>{@link #forCacheKey(String)} — when a secret must participate in an action key, emit a
 *       digest rather than the value. Matches how {@code VariantApply} secrets are folded into
 *       package keys.
 * </ul>
 */
public final class SecretRedactor {

    /** What a secret becomes in free-form text. */
    public static final String MASK = "***";

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
     * Build a redactor from an {@link EnvLookup}: every effective value that came from a
     * {@code .env} file (not the real environment).
     */
    /** Redactors are immutable; memo by value-set so per-line redaction reuses one (JK-1308). */
    private static final java.util.concurrent.ConcurrentHashMap<Set<String>, SecretRedactor> MEMO =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static SecretRedactor from(EnvLookup env) {
        Objects.requireNonNull(env, "env");
        Set<String> values = new LinkedHashSet<>();
        for (String name : env.fileNames()) {
            if (!env.isFromFile(name)) continue; // real env won — not a file secret
            String v = env.get(name);
            if (v != null && !v.isEmpty()) values.add(v);
        }
        if (values.isEmpty()) return NONE;
        if (MEMO.size() > 64) MEMO.clear(); // a handful of .env sets per engine; crude bound is fine
        return MEMO.computeIfAbsent(Set.copyOf(values), SecretRedactor::of);
    }

    /** Build a redactor from an explicit set of secret values (tests, side-channel secrets). */
    public static SecretRedactor of(Collection<String> values) {
        if (values == null || values.isEmpty()) return NONE;
        List<String> list = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isEmpty()) list.add(v);
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
