// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The environment of a child process jk starts, and the one place that builds or changes one.
 *
 * <p>Windows variable names are case-insensitive, and the system path is spelled {@code Path}.
 * {@link ProcessBuilder#environment()} there is a plain case-sensitive map: {@code get("PATH")}
 * misses {@code Path}, and a {@code put("PATH", ...)} lands beside it, so the child is handed
 * both and reads whichever the OS meets first (on Windows 11 the new one, which lost System32).
 * A {@code new LinkedHashMap<>(env)} copy has the same blind spot. Every child environment is
 * therefore built with {@link #map()} or {@link #copyOf}, changed with {@link #put}, and handed to
 * a process with {@link #replace} or {@link #putAll}, which keep one entry per name whatever its
 * case.
 *
 * <p>It is also where the one variable jk keeps from every child is kept out ({@link #withheld}):
 * the remote cache key seed, which jk alone reads. A child starts from a copy of whichever
 * environment its parent had, and the engine's is the shell that started it, so a seed exported
 * there would otherwise reach every program the engine starts that is not a worker (npm installing
 * jk's graph scripts, the AOT trainer, {@code git}, {@code jlink}, a notifier). {@link #replace},
 * {@link #put} and {@link #putAll} leave it out of what they hand over, and {@link #start} takes it
 * out of what the child would inherit; the engine starts every process through {@link #start} (the
 * {@code child-start-owner} guard).
 */
public final class ChildEnv {

    /**
     * The team's remote cache key seed. jk alone reads it, from the shell that runs {@code jk}: no
     * child process is handed it and no key holds it.
     */
    public static final String CACHE_KEY_SEED = "JK_CACHE_KEY_SEED";

    private ChildEnv() {}

    /**
     * Whether {@code name} is kept from every child jk starts and from every key: {@link
     * #CACHE_KEY_SEED}, in any case, since a Windows name matches so. The one rule; {@code
     * BuildEnv.withheld} is this.
     */
    public static boolean withheld(String name) {
        return CACHE_KEY_SEED.equalsIgnoreCase(name);
    }

    /** Takes every {@link #withheld} name out of what {@code pb}'s child would get, under any spelling. */
    public static ProcessBuilder withhold(ProcessBuilder pb) {
        pb.environment().keySet().removeIf(ChildEnv::withheld);
        return pb;
    }

    /** Starts {@code pb}'s child without the {@link #withheld} names: how jk's engine starts a process. */
    public static Process start(ProcessBuilder pb) throws IOException {
        return withhold(pb).start();
    }

    /** An empty environment map that, on Windows, treats {@code Path} and {@code PATH} as one name. */
    public static Map<String, String> map() {
        return map(Os.isWindows());
    }

    /** {@link #map()} for a platform whose names are case-insensitive or not. */
    public static Map<String, String> map(boolean caseInsensitive) {
        return caseInsensitive ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new LinkedHashMap<>();
    }

    /** A mutable copy of {@code env} keyed as {@link #map()} keys; of two spellings the later wins. */
    public static Map<String, String> copyOf(Map<String, String> env) {
        return copyOf(env, Os.isWindows());
    }

    /** {@link #copyOf(Map)} for a platform whose names are case-insensitive or not. */
    public static Map<String, String> copyOf(Map<String, String> env, boolean caseInsensitive) {
        Map<String, String> out = map(caseInsensitive);
        putAll(out, env, caseInsensitive);
        return out;
    }

    /**
     * {@code env}'s value for {@code name}, under any spelling on a case-insensitive platform, or
     * {@code null}. Works on any map, a {@link ProcessBuilder#environment()} included.
     */
    public static @Nullable String get(Map<String, String> env, String name) {
        return get(env, name, Os.isWindows());
    }

    /** {@link #get(Map, String)} for a platform whose names are case-insensitive or not. */
    public static @Nullable String get(Map<String, String> env, String name, boolean caseInsensitive) {
        String exact = env.get(name);
        if (exact != null || !caseInsensitive) return exact;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /**
     * Sets {@code name} in {@code env}, replacing the value of any other spelling of it on a
     * case-insensitive platform under the spelling already there ({@code Path} stays {@code Path}).
     * Works on any map, a {@link ProcessBuilder#environment()} included.
     */
    public static void put(Map<String, String> env, String name, String value) {
        put(env, name, value, Os.isWindows());
    }

    /** {@link #put(Map, String, String)} for a platform whose names are case-insensitive or not. */
    public static void put(Map<String, String> env, String name, String value, boolean caseInsensitive) {
        if (!caseInsensitive || env.containsKey(name)) {
            env.put(name, value);
            return;
        }
        String kept = null;
        for (Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if (!key.equalsIgnoreCase(name)) continue;
            if (kept == null) {
                kept = key;
            } else {
                it.remove();
            }
        }
        env.put(kept == null ? name : kept, value);
    }

    /** Removes {@code name} from {@code env}, under every spelling on a case-insensitive platform. */
    public static void remove(Map<String, String> env, String name, boolean caseInsensitive) {
        if (caseInsensitive) {
            env.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        } else {
            env.remove(name);
        }
    }

    /** {@link #put} of every entry of {@code more}, in its order. */
    public static void putAll(Map<String, String> env, Map<String, String> more) {
        putAll(env, more, Os.isWindows());
    }

    /** {@link #putAll(Map, Map)} for a platform whose names are case-insensitive or not. */
    public static void putAll(Map<String, String> env, Map<String, String> more, boolean caseInsensitive) {
        for (Map.Entry<String, String> e : more.entrySet()) put(env, e.getKey(), e.getValue(), caseInsensitive);
    }

    /** Gives {@code pb}'s child exactly {@code env}, one entry per name whatever its case, less the {@link #withheld}. */
    public static ProcessBuilder replace(ProcessBuilder pb, Map<String, String> env) {
        Map<String, String> target = pb.environment();
        target.clear();
        putAll(target, env);
        return withhold(pb);
    }

    /** Sets {@code name} for {@code pb}'s child, replacing any other spelling of it ({@link #put}). */
    public static ProcessBuilder put(ProcessBuilder pb, String name, String value) {
        put(pb.environment(), name, value);
        return withhold(pb);
    }

    /** Lays {@code more} over what {@code pb}'s child inherits, one entry per name whatever its case. */
    public static ProcessBuilder putAll(ProcessBuilder pb, Map<String, String> more) {
        putAll(pb.environment(), more);
        return withhold(pb);
    }
}
