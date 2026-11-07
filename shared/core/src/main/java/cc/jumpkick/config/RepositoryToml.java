// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.RepositoryMirror;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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
                    throw new JkBuildParseException("repositories.jk-local: name reserved for jk; pick another name");
                }
                continue;
            }
            // The list form: a quoted name such as "nexus.internal" is one key, not a path.
            Object value = repos.get(List.of(name));
            if (isMirror(value)) {
                // A mirror is this machine's network, read by mirrors() from the user's config alone.
                if (onBad == OnBad.REJECT) throw new JkBuildParseException(mirrorInProject(name));
                continue;
            }
            try {
                RepositorySpec spec = entry(name, value, vars, onBad);
                if (spec != null) result.add(spec);
            } catch (UserConfigParseException unparsed) {
                throw unparsed; // config.toml's own error, with its position — never this site's fallback
            } catch (JkBuildParseException e) {
                if (onBad == OnBad.REJECT) throw e;
            } catch (RuntimeException e) {
                if (onBad == OnBad.REJECT) throw new JkBuildParseException(e.getMessage(), e);
            }
        }
        return result;
    }

    /** The key that makes a {@code [repositories.<name>]} table of {@code ~/.jk/config.toml} a mirror. */
    public static final String MIRROR_OF = "mirror-of";

    /** Every key a mirror table may carry; any other key fails it. */
    public static final List<String> MIRROR_KEYS =
            List.of("url", MIRROR_OF, "token", "username", "password", "allow-insecure");

    /** True when {@code value} is a table carrying {@code mirror-of}. */
    private static boolean isMirror(@Nullable Object value) {
        return value instanceof TomlTable t && t.contains(List.of(MIRROR_OF));
    }

    /** Why a project manifest may not declare a mirror, naming the table and where it belongs. */
    static String mirrorInProject(String name) {
        return "repositories." + name + "." + MIRROR_OF + ": mirrors go in ~/.jk/config.toml, not jk.toml";
    }

    /**
     * Every {@code [repositories.<name>]} table of the user's config that carries {@code
     * mirror-of}, in declaration order: the mirrors {@code file} declares. Strict, unlike the
     * repositories beside them — a mirror the parse dropped would send its repositories to their own
     * URLs, which is the one thing a mirror exists to prevent — so a malformed mirror is a {@link
     * JkBuildParseException} naming the table and the file.
     */
    public static List<RepositoryMirror> mirrors(@Nullable TomlTable repos, Path file) {
        if (repos == null) return List.of();
        List<RepositoryMirror> result = new ArrayList<>();
        for (String name : repos.keySet()) {
            Object value = repos.get(List.of(name));
            if (!isMirror(value)) continue;
            result.add(mirror(name, (TomlTable) Objects.requireNonNull(value), file));
        }
        return List.copyOf(result);
    }

    private static RepositoryMirror mirror(String name, TomlTable t, Path file) {
        String where = "repositories." + name + " in " + file;
        for (String key : t.keySet()) {
            if (!MIRROR_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        where + ": a mirror takes only " + String.join(", ", MIRROR_KEYS) + ", not `" + key + "`");
            }
        }
        String url = t.getString("url");
        if (url == null) throw new JkBuildParseException(where + " requires a string `url` field");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(where + " has malformed URL: " + url, e);
        }
        boolean allowInsecure;
        try {
            allowInsecure = flag(t, "allow-insecure", where);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(e.getMessage(), e);
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecure && !loopback(uri.getHost())) {
            throw new JkBuildParseException(plaintextRefusal("repositories." + name, url) + " (" + file + ")");
        }
        Optional<RepoCredential> credential =
                credential(t, raw -> interpolate(raw, VarPolicy.LENIENT, "repositories." + name));
        return new RepositoryMirror(name, uri, mirrorOf(t, where), credential.orElse(null), allowInsecure, file);
    }

    /** {@code mirror-of} as a non-empty list of non-empty strings. */
    private static MirrorOf mirrorOf(TomlTable t, String where) {
        if (!(t.get(MIRROR_OF) instanceof TomlArray arr) || arr.isEmpty()) {
            throw new JkBuildParseException(
                    where + ".mirror-of must be a non-empty array of repository ids (e.g. [\"*\", \"!jumpkick\"])");
        }
        List<String> tokens = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String token) || token.isBlank()) {
                throw new JkBuildParseException(where + ".mirror-of[" + i + "] must be a non-empty string");
            }
            tokens.add(token);
        }
        return new MirrorOf(tokens);
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
            "allow-unverified",
            "releases",
            "snapshots",
            "blocked",
            "optional");

    /** One entry; {@code null} when it is malformed and the layer skips rather than rejects. */
    private static @Nullable RepositorySpec entry(String name, @Nullable Object value, VarPolicy vars, OnBad onBad) {
        String where = "repositories." + name;
        String url;
        Optional<RepoCredential> credential = Optional.empty();
        Optional<ObjectStoreConfig> objectStore = Optional.empty();
        List<String> groups = List.of();
        boolean allowInsecure = false;
        boolean allowUnverified = false;
        boolean releases = true;
        boolean snapshots = true;
        boolean blocked = false;
        boolean optional = false;
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
                releases = flag(t, "releases", where, true);
                snapshots = flag(t, "snapshots", where, true);
                blocked = flag(t, "blocked", where);
                optional = flag(t, "optional", where);
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
            throw new JkBuildParseException(
                    where + ": allow-insecure and allow-unverified are not allowed on Maven Central");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where + " has malformed URL: " + url, e);
        }
        if (blocked && allowInsecure) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(where + " sets both blocked and allow-insecure; drop one");
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecure && !blocked && !loopback(uri.getHost())) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(plaintextRefusal(where, url));
        }
        if (!releases && !snapshots) {
            if (onBad == OnBad.SKIP) return null;
            throw new JkBuildParseException(
                    where + " serves neither releases nor snapshots: nothing would be asked of it");
        }
        return new RepositorySpec(
                name,
                uri,
                credential.orElse(null),
                objectStore.orElse(null),
                groups,
                allowInsecure,
                allowUnverified,
                releases,
                snapshots,
                blocked,
                optional);
    }

    /**
     * The sentence a plaintext {@code http://} repository is refused with: at {@code where} ({@code
     * repositories.<name>}) with its URL, the threat, and the two ways out. The manifest parse
     * throws it; a blocked repository's lock failure carries it once nothing else serves a package.
     */
    public static String plaintextRefusal(String where, String url) {
        return where + ": " + url + " is plain http; use https or set allow-insecure = true";
    }

    /** The boolean at {@code key}, {@code false} when absent; any other type is an error naming the position. */
    private static boolean flag(TomlTable t, String key, String where) {
        return flag(t, key, where, false);
    }

    /** The boolean at {@code key}, {@code absent} when missing; any other type is an error naming the position. */
    private static boolean flag(TomlTable t, String key, String where, boolean absent) {
        Object raw = t.get(key);
        if (raw == null) return absent;
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(where + "." + key + " must be true or false");
    }

    /** Expand {@code raw} under {@code policy} against the process environment; {@code where} names the position. */
    public static @Nullable String interpolate(@Nullable String raw, VarPolicy policy, String where) {
        return interpolate(raw, policy, where, BuildEnv::get);
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
     * Expand {@code ${VAR}} and {@code ${env:VAR}} references in {@code raw}, resolving each var name
     * via {@code resolveVar} (which returns the replacement, or may throw for a strict "unset var"
     * policy). {@code null} in → {@code null} out. A {@code ${file:...}} reference is left as
     * written: only {@link ReferenceResolver} reads one, under the declaring file's provenance.
     */
    public static @Nullable String interpolate(@Nullable String raw, Function<String, String> resolveVar) {
        if (raw == null) return null;
        return ConfigReference.expand(
                raw, ConfigReference::parse, ref -> ref.isEnv() ? resolveVar.apply(ref.body()) : null);
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
    /** See {@link RepositorySpec#loopback}. */
    static boolean loopback(@Nullable String host) {
        return RepositorySpec.loopback(host);
    }
}
