// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The one reader of a {@code [repositories]} table, and the field parser under it ({@code ${ENV}}
 * interpolation, credentials, object-store, exclusive {@code groups}).
 *
 * <p>Two files declare repositories with the same vocabulary and two different temperaments: a
 * project {@code jk.toml} must fail loudly on a table that lies, while {@code
 * ~/.jk/config.toml} must never fail a build over a machine-local preference. Those are two
 * <em>policies</em>, {@link VarPolicy} and {@link OnBad} — not two readers.
 */
public final class RepositoryToml {

    private RepositoryToml() {}

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** What a {@code ${VAR}} reference means to a layer. */
    public enum VarPolicy {
        /**
         * Leave the reference alone. The project manifest's policy: expansion happens at the
         * credential resolver, so a parsed {@code JkBuild} never carries a secret.
         */
        DEFER,
        /**
         * An unset variable stays the literal {@code ${VAR}} text. The user-config layer's policy —
         * global config must never fail a build.
         */
        LENIENT,
        /** An unset variable is an error naming the position. The publish path's policy. */
        STRICT
    }

    /** What a malformed entry means to a layer. */
    public enum OnBad {
        /** Reject the document. A project manifest that lies about a repository is a build error. */
        REJECT,
        /** Drop the entry and carry on. */
        SKIP
    }

    /**
     * Every {@code [repositories.<name>]} entry in {@code repos}, in declaration order. Empty when
     * the table is absent.
     *
     * @param onBad {@link OnBad#REJECT} throws {@link JkBuildParseException} on the first bad entry;
     *     {@link OnBad#SKIP} drops it
     */
    public static List<RepositorySpec> repositories(@Nullable TomlTable repos, VarPolicy vars, OnBad onBad) {
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            if (RepositorySpec.JK_LOCAL.equals(name)) {
                // The first-party install store (repos/jk-local) is not a user's to redeclare.
                if (onBad == OnBad.REJECT) {
                    throw new JkBuildParseException(
                            "repositories.jk-local is reserved for JumpKick's first-party install store"
                                    + " (repos/jk-local); pick another repository name");
                }
                continue;
            }
            try {
                RepositorySpec spec = entry(name, repos.get(name), vars, onBad);
                if (spec != null) result.add(spec);
            } catch (JkBuildParseException e) {
                if (onBad == OnBad.REJECT) throw e;
            } catch (RuntimeException e) {
                if (onBad == OnBad.REJECT) throw new JkBuildParseException(e.getMessage(), e);
            }
        }
        return result;
    }

    /** Every key a {@code [repositories.<name>]} table may carry; any other key fails the entry. */
    public static final List<String> REPOSITORY_KEYS = List.of(
            "url",
            "token",
            "username",
            "password",
            "region",
            "endpoint",
            "access-key",
            "secret-key",
            "session-token",
            "groups",
            "allow-insecure",
            "allow-unverified");

    /** One entry; {@code null} when it is malformed and the layer skips rather than rejects. */
    private static @Nullable RepositorySpec entry(String name, @Nullable Object value, VarPolicy vars, OnBad onBad) {
        String where = "repositories." + name;
        String url;
        Optional<RepoCredential> credential = Optional.empty();
        Optional<ObjectStoreConfig> objectStore = Optional.empty();
        List<String> groups = List.of();
        boolean allowInsecure = false;
        boolean allowUnverified = false;
        if (value instanceof String s) {
            url = s;
        } else if (value instanceof TomlTable t) {
            for (String key : t.keySet()) {
                if (!REPOSITORY_KEYS.contains(key)) {
                    if (onBad == OnBad.SKIP) return null;
                    throw new JkBuildParseException(where + " unknown key `" + key + "` — expected one of: "
                            + String.join(", ", REPOSITORY_KEYS));
                }
            }
            url = t.getString("url");
            if (url == null) {
                if (onBad == OnBad.SKIP) return null;
                throw new JkBuildParseException(where + " requires a string `url` field");
            }
            Function<@Nullable String, @Nullable String> interp = raw -> interpolate(raw, vars, where);
            credential = credential(t, interp);
            objectStore = objectStore(t, interp);
            try {
                groups = groups(t, where);
                allowInsecure = flag(t, "allow-insecure", where);
                allowUnverified = flag(t, "allow-unverified", where);
            } catch (IllegalArgumentException e) {
                if (onBad == OnBad.SKIP) return null;
                throw new JkBuildParseException(e.getMessage(), e);
            }
        } else {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where + " must be a URL string or an inline table with `url`");
        }
        if (RepositorySpec.CENTRAL.equals(name) && (allowInsecure || allowUnverified)) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where
                    + " is Maven Central, which serves https and publishes a checksum for every artifact:"
                    + " allow-insecure and allow-unverified are not accepted on it");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where + " has malformed URL: " + url, e);
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecure) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where + " uses plaintext http:// (" + url
                    + "): anyone on the network path can replace the bytes jk pins into jk-lock.toml."
                    + " Use https, or set allow-insecure = true on [" + where + "] to accept that.");
        }
        return new RepositorySpec(
                name, uri, credential.orElse(null), objectStore.orElse(null), groups, allowInsecure, allowUnverified);
    }

    /** The boolean at {@code key}, {@code false} when absent; any other type is an error naming the position. */
    private static boolean flag(TomlTable t, String key, String where) {
        Object raw = t.get(key);
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(where + "." + key + " must be true or false");
    }

    /** Expand {@code raw} under {@code policy} against the process environment; {@code where} names the position. */
    public static @Nullable String interpolate(@Nullable String raw, VarPolicy policy, String where) {
        return interpolate(raw, policy, where, System::getenv);
    }

    /**
     * As {@link #interpolate(String, VarPolicy, String)} but resolving against {@code env} — the
     * build path expands object-store credentials against the layered request environment
     * ({@code .env} under the caller's shell), not the engine process's environ.
     */
    public static @Nullable String interpolate(
            @Nullable String raw, VarPolicy policy, String where, Function<String, @Nullable String> env) {
        return switch (policy) {
            case DEFER -> raw;
            case LENIENT ->
                interpolate(raw, var -> {
                    String v = env.apply(var);
                    return v != null ? v : "${" + var + "}";
                });
            case STRICT ->
                interpolate(raw, var -> {
                    String v = env.apply(var);
                    if (v == null) {
                        throw new JkBuildParseException(
                                where + " references unset environment variable ${" + var + "}");
                    }
                    return v;
                });
        };
    }

    /**
     * Bearer beats basic — the one place that decides what a {@code token} / {@code username} /
     * {@code password} triple means. Blank is absent.
     */
    public static Optional<RepoCredential> credentialOf(
            @Nullable String token, @Nullable String username, @Nullable String password) {
        if (token != null && !token.isBlank()) return Optional.of(new RepoCredential.Bearer(token));
        if (username != null && !username.isBlank()) {
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    /**
     * Expand {@code ${VAR}} references in {@code raw}, resolving each var name via {@code resolveVar}
     * (which returns the replacement, or may throw for a strict "unset var" policy). {@code null} in →
     * {@code null} out.
     */
    public static @Nullable String interpolate(@Nullable String raw, Function<String, String> resolveVar) {
        if (raw == null) return null;
        Matcher m = ENV_REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(resolveVar.apply(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Bearer ({@code token}) or basic ({@code username}/{@code password}) credential on the table, or
     * empty. {@code interp} applies the caller's {@code ${ENV}} interpolation to each value.
     */
    public static Optional<RepoCredential> credential(
            TomlTable t, Function<@Nullable String, @Nullable String> interp) {
        return credentialOf(
                interp.apply(t.getString("token")),
                interp.apply(t.getString("username")),
                interp.apply(t.getString("password")));
    }

    /**
     * Object-store config ({@code region}/{@code endpoint}/{@code access-key}/{@code secret-key}/
     * {@code session-token}) for s3://gs:// backends, or empty when none set. {@code interp} applies
     * the caller's {@code ${ENV}} interpolation.
     */
    public static Optional<ObjectStoreConfig> objectStore(
            TomlTable t, Function<@Nullable String, @Nullable String> interp) {
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(interp.apply(t.getString("region"))),
                blankToNull(interp.apply(t.getString("endpoint"))),
                blankToNull(interp.apply(t.getString("access-key"))),
                blankToNull(interp.apply(t.getString("secret-key"))),
                blankToNull(interp.apply(t.getString("session-token"))));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Exclusive Maven group bindings ({@code groups = ["com.acme", "com.acme.*"]}) for.
     * Empty when absent. Strict callers pass a path prefix for error messages; invalid types throw
     * {@link IllegalArgumentException}.
     */
    public static List<String> groups(TomlTable t, String pathForErrors) {
        if (t == null || !t.contains("groups")) return List.of();
        Object raw = t.get("groups");
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new IllegalArgumentException(
                    pathForErrors + ".groups must be an array of strings (e.g. [\"com.acme\", \"com.acme.*\"])");
        }
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object el = arr.get(i);
            if (!(el instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(pathForErrors + ".groups[" + i + "] must be a non-empty string");
            }
            out.add(s.trim());
        }
        return List.copyOf(out);
    }
}
