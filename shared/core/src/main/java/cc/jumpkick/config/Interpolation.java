// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Where {@code ${VAR}} may appear in a {@code jk.toml}, and what happens where it may not (JK-1271).
 *
 * <h2>The rule</h2>
 *
 * <b>The environment may influence where jk talks to, and what a spawned process sees — never what
 * gets compiled or how.</b>
 *
 * <p>That is not conservatism for its own sake. {@code jk.toml} plus {@code jk-lock.toml} have to fully
 * describe the artifact, and the action cache turns a leak into something worse than plain
 * non-determinism: an environment-sourced value inside a cache key means CI and a laptop never share
 * cache, and one that reaches a compiler flag <em>without</em> reaching the key produces silently
 * stale artifacts. So interpolation is allowed at a whitelist of positions rather than generally.
 *
 * <h2>Allowed</h2>
 *
 * <ul>
 *   <li>{@code [repositories.<name>]} credentials — {@code username}, {@code password}, {@code token}
 *   <li>{@code [repositories.<name>]} object-store keys — {@code region}, {@code endpoint},
 *       {@code access-key}, {@code secret-key}, {@code session-token}
 *   <li>{@code [test] env} values — what a forked test JVM sees
 * </ul>
 *
 * <h2>Not allowed, and why</h2>
 *
 * Versions, coordinates, source roots, {@code extra-src}, {@code extra-resources}, compiler args,
 * {@code ksp-options}, toolchain pins — all of them feed a compile or package action key.
 *
 * <p><b>Repository URLs are also excluded</b>, which surprises people: the lockfile records a
 * repository's URL, so an environment-dependent URL would make a committed lock differ between
 * machines built from the same commit. Credentials never reach the lock, which is exactly why they
 * are safe.
 *
 * <h2>A non-whitelisted reference is an error</h2>
 *
 * Not a silent literal. Someone who writes {@code version = "${MY_VERSION}"} has a clear intent, and
 * leaving it as the literal text {@code ${MY_VERSION}} would fail much later as a bewildering
 * "no such version" — or worse, quietly resolve to something that happens to exist.
 */
public final class Interpolation {

    /** {@code ${NAME}} — the same shape {@link RepositoryToml} expands. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Dotted paths where a reference is honoured; {@code *} matches one path segment. */
    private static final List<String> ALLOWED = List.of(
            "repositories.*.username",
            "repositories.*.password",
            "repositories.*.token",
            "repositories.*.region",
            "repositories.*.endpoint",
            "repositories.*.access-key",
            "repositories.*.secret-key",
            "repositories.*.session-token",
            "test.env.*");

    private Interpolation() {}

    /**
     * Reject {@code ${VAR}} outside {@link #ALLOWED}, naming the offending position.
     *
     * <p>Called once per parse. Cheap: it only walks string values, and only those containing
     * {@code $}.
     */
    public static void guard(TomlTable root) {
        List<String> offenders = new java.util.ArrayList<>();
        walk(root, "", offenders);
        if (offenders.isEmpty()) return;
        throw new JkBuildParseException("environment references are not allowed here: "
                + String.join("; ", offenders)
                + ". ${VAR} is honoured only in repository credentials, repository object-store keys,"
                + " and [test] env — anything that feeds a compile or package cache key must stay a"
                + " literal, or the same commit would build differently on different machines.");
    }

    private static void walk(TomlTable table, String prefix, List<String> offenders) {
        for (String key : table.keySet()) {
            Object value = table.get(List.of(key));
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof TomlTable nested) {
                walk(nested, path, offenders);
            } else if (value instanceof TomlArray array) {
                walkArray(array, path, offenders);
            } else if (value instanceof String s) {
                check(s, path, offenders);
            }
        }
    }

    private static void walkArray(TomlArray array, String path, List<String> offenders) {
        for (int i = 0; i < array.size(); i++) {
            Object element = array.get(i);
            if (element instanceof TomlTable nested) {
                walk(nested, path + "[" + i + "]", offenders);
            } else if (element instanceof TomlArray nested) {
                walkArray(nested, path + "[" + i + "]", offenders);
            } else if (element instanceof String s) {
                check(s, path + "[" + i + "]", offenders);
            }
        }
    }

    private static void check(String value, String path, List<String> offenders) {
        if (value.indexOf('$') < 0) return;
        Matcher m = REFERENCE.matcher(value);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) found.add(m.group(1));
        if (found.isEmpty() || allowed(path)) return;
        for (String var : found) offenders.add(path + " (${" + var + "})");
    }

    /** True when {@code path} matches an allowed pattern; array indices never match. */
    static boolean allowed(String path) {
        for (String pattern : ALLOWED) {
            if (matches(pattern, path)) return true;
        }
        return false;
    }

    private static boolean matches(String pattern, String path) {
        String[] p = pattern.split("\\.");
        String[] a = path.split("\\.");
        if (p.length != a.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (p[i].equals("*")) {
                if (a[i].indexOf('[') >= 0) return false; // an array element is never a whitelisted slot
                continue;
            }
            if (!p[i].equals(a[i])) return false;
        }
        return true;
    }

    /**
     * Expand {@code ${VAR}} in {@code raw} through {@code env}, strictly.
     *
     * <p>An unset variable is an error rather than an empty string: silent emptiness is how a build
     * "succeeds" while authenticating anonymously, or points a tool at the wrong place.
     * {@code describe} names the position so the message is actionable.
     */
    public static String expand(String raw, String describe, UnaryOperator<String> env) {
        if (raw == null || raw.indexOf('$') < 0) return raw;
        return RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new JkBuildParseException(
                        describe + " references unset environment variable ${" + var + "}");
            }
            return value;
        });
    }
}
