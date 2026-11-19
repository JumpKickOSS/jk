// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
 * <p>It is also where jk's own secrets are kept out ({@link #withheld}): the remote cache key seed
 * and the credentials jk reads for itself ({@link #credential}: repository and cache tokens, forge
 * tokens, the signing passphrase). A child starts from a copy of whichever environment its
 * parent had, and the engine's is the shell that started it, so a token exported there would
 * otherwise reach every program the engine starts that is not a worker (npm installing jk's graph
 * scripts, the AOT trainer, {@code git}, {@code jlink}, a notifier). {@link #replace}, {@link #put}
 * and {@link #putAll} leave them out of what they hand over, and {@link #start} takes them out of
 * what the child would inherit; the engine starts every process through {@link #start} (the {@code
 * child-start-owner} guard).
 *
 * <p>A credential a module's manifest forwards by name is the one exception, and the environment
 * itself carries the consent: the engine composing a worker's environment names such a credential
 * in {@link #FORWARDED}, and every {@link #withhold} keeps a credential that environment names, so
 * a program a plugin step starts gets it too. The seed is never kept ({@link #refused}): a manifest
 * may not name it at all.
 */
public final class ChildEnv {

    /**
     * The team's remote cache key seed. jk alone reads it, from the shell that runs {@code jk}: no
     * child process is handed it and no key holds it.
     */
    public static final String CACHE_KEY_SEED = "JK_CACHE_KEY_SEED";

    /**
     * The jk credentials a manifest forwarded by name into this environment, comma-separated: set
     * by the engine on the environment it composes for a worker, never by a shell. {@link #withhold}
     * keeps a {@link #credential} this names.
     */
    public static final String FORWARDED = "JK_ENV_FORWARDED";

    /**
     * jk's own credentials, by name: a repository's or remote cache's {@code JK_REPO_<ID>_TOKEN},
     * {@code _USERNAME} and {@code _PASSWORD}, and any other {@code JK_REPO_<ID>_} name whose last
     * word says it is one (an S3 repository's {@code _ACCESS_KEY} and {@code _SECRET_KEY}, which a
     * manifest names as {@code ${JK_REPO_<ID>_…}}), each with its {@code _FILE} form; a forge's
     * {@code JK_<FORGE>_TOKEN}; {@code JK_GPG_PASSPHRASE}: the names in jk's own namespace that
     * hold its credentials. {@code JK_REPO_<ID>_HOST} and other words ({@code _REGION}) are not. A
     * name other tools read too ({@code GITHUB_TOKEN}, the AWS chain, {@code jk publish}'s {@code
     * PUBLISH_PASSWORD}) is the shell's, not jk's, and is left alone. Matched in any case, since a
     * Windows name matches so.
     */
    private static final Pattern CREDENTIAL = Pattern.compile(
            "JK_REPO_.+_(TOKEN|USERNAME|USER|PASSWORD|PASSWD|PASSPHRASE|SECRET|KEY|CREDENTIALS?)(_FILE)?"
                    + "|JK_(GITHUB|GITLAB|GITEA|BITBUCKET)_TOKEN"
                    + "|JK_GPG_PASSPHRASE",
            Pattern.CASE_INSENSITIVE);

    private ChildEnv() {}

    /**
     * Whether {@code name} is kept from every child jk starts and from every key unless a manifest
     * forwards it by name: the {@link #refused} seed, or one of jk's own {@link #credential}s. The
     * one rule; {@code BuildEnv.withheld} is this.
     */
    public static boolean withheld(String name) {
        return refused(name) || credential(name);
    }

    /**
     * Whether {@code name} is withheld even from a manifest that names it: {@link #CACHE_KEY_SEED},
     * in any case. A manifest that names it is refused at parse.
     */
    public static boolean refused(String name) {
        return CACHE_KEY_SEED.equalsIgnoreCase(name);
    }

    /**
     * Whether {@code name} is one of jk's own credentials: {@code JK_REPO_<ID>_TOKEN}, {@code
     * _USERNAME}, {@code _PASSWORD}, {@code _ACCESS_KEY}, {@code _SECRET_KEY} and the like, each
     * with its {@code _FILE} form, {@code JK_<FORGE>_TOKEN}, {@code JK_GPG_PASSPHRASE}.
     */
    public static boolean credential(String name) {
        return CREDENTIAL.matcher(name).matches();
    }

    /**
     * The {@link #FORWARDED} value naming {@code names}' credentials, upper-cased and sorted, or
     * null when none of them is one.
     */
    public static @Nullable String forwardedValue(Collection<String> names) {
        Set<String> out = new TreeSet<>();
        for (String name : names) {
            if (credential(name)) out.add(name.toUpperCase(Locale.ROOT));
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /** Whether {@code env} keeps {@code name}: not {@link #withheld}, or a credential its {@link #FORWARDED} names. */
    public static boolean kept(Map<String, String> env, String name) {
        if (!withheld(name)) return true;
        if (refused(name)) return false;
        String forwarded = get(env, FORWARDED);
        if (forwarded == null) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        for (String asked : forwarded.split(",")) {
            if (asked.strip().equalsIgnoreCase(upper)) return true;
        }
        return false;
    }

    /**
     * Takes every {@link #withheld} name out of what {@code pb}'s child would get, under any
     * spelling, but a credential its environment's {@link #FORWARDED} names.
     */
    public static ProcessBuilder withhold(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        Map<String, String> asked = Map.copyOf(env);
        env.keySet().removeIf(name -> !kept(asked, name));
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
