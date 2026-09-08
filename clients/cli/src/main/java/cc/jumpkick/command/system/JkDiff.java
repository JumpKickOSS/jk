// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.command.JkEnv;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Prior {@code jk hook-env} overrides for undo: base64 {@code KEY\\0previous\\n} in {@code __JK_DIFF}.
 * {@link #UNSET_SENTINEL} means the key was originally unset; next apply restores or re-exports.
 */
public final class JkDiff {

    /** Sentinel stored when the previous env had no entry for a key. */
    public static final String UNSET_SENTINEL = "\u0001__jk_unset__";

    private final Map<String, String> previous;

    public JkDiff(Map<String, String> previous) {
        this.previous = new LinkedHashMap<>(previous);
    }

    public static JkDiff empty() {
        return new JkDiff(Map.of());
    }

    /**
     * Parse a base64-encoded diff payload. Empty or malformed input yields an empty diff (we never
     * want a bad serialization to wedge the shell).
     */
    public static JkDiff parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return empty();
        try {
            var decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            var map = new LinkedHashMap<String, String>();
            for (var line : decoded.split("\n")) {
                if (line.isEmpty()) continue;
                int sep = line.indexOf('\0');
                if (sep < 0) continue;
                map.put(line.substring(0, sep), line.substring(sep + 1));
            }
            return new JkDiff(map);
        } catch (IllegalArgumentException e) {
            return empty();
        }
    }

    /** Serialize as a base64-encoded string suitable for an env var value. */
    public String encode() {
        if (previous.isEmpty()) return "";
        var sb = new StringBuilder();
        previous.forEach((k, v) -> sb.append(k).append('\0').append(v).append('\n'));
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Keys currently tracked. */
    public Set<String> keys() {
        return previous.keySet();
    }

    /**
     * Previous value for {@code key}; {@code null} if the diff has no entry, or the {@link
     * #UNSET_SENTINEL} when the key was unset in the original environment.
     */
    public @Nullable String previousValue(String key) {
        return previous.get(key);
    }

    /** True iff the recorded "previous" value means the key was unset. */
    public boolean wasUnset(String key) {
        return UNSET_SENTINEL.equals(previous.get(key));
    }

    /**
     * Build the diff that will be stored after applying {@code target}: the union of
     * previously-tracked keys and the keys {@code target} overrides, each carrying the value the env
     * held <em>before</em> {@code jk} touched it. {@code PATH} is never tracked — toolchain bins are
     * swapped surgically on the live search path so a frozen prior PATH cannot clobber neighbors.
     */
    public JkDiff next(JkEnv.Target target, EnvSnapshot current) {
        var combined = new LinkedHashMap<String, String>();
        for (var key : target.vars().keySet()) {
            if (JkEnv.PATH.equals(key)) continue;
            // Reuse the prior diff's "before" value when we still own the key —
            // otherwise reach into the live env and capture (or sentinel) it.
            if (previous.containsKey(key)) {
                combined.put(key, previous.get(key));
            } else {
                var cur = current.get(key);
                combined.put(key, cur == null ? UNSET_SENTINEL : cur);
            }
        }
        return new JkDiff(combined);
    }

    /** Restore-source the live environment, used to seed {@link #next}. */
    @FunctionalInterface
    public interface EnvSnapshot {
        String get(String key);

        static EnvSnapshot fromSystem() {
            return System::getenv;
        }
    }
}
