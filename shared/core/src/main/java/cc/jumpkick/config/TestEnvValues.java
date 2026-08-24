// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.Hashing;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * The one expansion of {@code [test] env}.
 *
 * <p>Two callers want the same values for different purposes: the environment a forked test JVM
 * receives, and the run-tests action key that decides whether that JVM is forked at all. They used
 * to expand the manifest separately, and they disagreed about the one thing that must never differ.
 * The launch side failed the build on an unset {@code ${VAR}}; the key side caught that same failure
 * and keyed on the raw {@code ${VAR}} text. So a manifest jk could not launch forecast as "tests
 * cached", and whether a build failed became a function of what was already in the cache rather than
 * of the manifest. That is the reproducibility fence falling over: the environment may change what a
 * spawned process sees, never whether the same commit builds.
 *
 * <p>What must agree is therefore written once here, above the switch — the reference syntax
 * ({@link Interpolation}), and that an unset variable is a {@link JkBuildParseException} in every
 * mode. What genuinely differs is the {@link Mode}: what {@code ${target}} and {@code ${module}}
 * stand for, and whether a resolved value may be written down verbatim.
 */
public final class TestEnvValues {

    /** The module's build output directory. */
    private static final String TARGET = "target";

    /** The module directory itself. */
    private static final String MODULE = "module";

    private TestEnvValues() {}

    /**
     * What the resolved values are for.
     *
     * <p>Sealed, and matched exhaustively with no {@code default} arm: a third consumer has to
     * answer both questions for itself rather than inherit whichever answer it happened to fall
     * through to. That inheritance is exactly how the two expansions drifted apart.
     */
    public sealed interface Mode {

        /**
         * The forked test JVM's environment. {@code ${target}} and {@code ${module}} become the real
         * directories the JVM will read and write, and a resolved value is handed over as it is —
         * the child needs the actual token, not a digest of it.
         *
         * @param env the build's environment, normally {@link BuildEnv#forModule(Path)}
         */
        record Launch(UnaryOperator<String> env) implements Mode {
            public Launch {
                Objects.requireNonNull(env, "env");
            }
        }

        /**
         * The run-tests action key. {@code ${target}} and {@code ${module}} stay literal tokens so
         * two checkouts of one commit produce one key, and any value that resolved through the
         * environment is hashed rather than written out: a changed environment must retest, but
         * neither a {@code .env} secret nor a machine-specific path may land in a key that a second
         * machine may read.
         *
         * @param lookup resolves {@code ${VAR}} and says which values came from a {@code .env}
         * @param secrets {@link SecretRedactor#from} that same {@code lookup} — a redactor built
         * from a different environment would mask the wrong values
         */
        record CacheKey(EnvLookup lookup, SecretRedactor secrets) implements Mode {
            public CacheKey {
                Objects.requireNonNull(lookup, "lookup");
                Objects.requireNonNull(secrets, "secrets");
            }
        }
    }

    /**
     * {@code declared}'s values resolved for {@code mode}, ordered by key so a cache key built from
     * the result does not depend on the order the manifest happened to list them in.
     *
     * <p>{@code moduleDir} and {@code target} are what the two path tokens stand for. {@link
     * Mode.CacheKey} preserves those tokens deliberately and so reads neither; only {@link
     * Mode.Launch} needs them.
     *
     * @throws JkBuildParseException if a value references an environment variable that is not set —
     * in both modes, which is the whole point of this type
     */
    public static Map<String, String> resolve(Map<String, String> declared, Path moduleDir, Path target, Mode mode) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(declared).entrySet()) {
            String where = "[test].env." + e.getKey();
            String raw = e.getValue() == null ? "" : e.getValue();
            out.put(
                    e.getKey(),
                    switch (mode) {
                        case Mode.Launch launch ->
                            Interpolation.expand(raw, where, name -> switch (name) {
                                case TARGET -> absolute(target, TARGET);
                                case MODULE -> absolute(moduleDir, MODULE);
                                default -> launch.env().apply(name);
                            });
                        case Mode.CacheKey key -> keyed(raw, where, key);
                    });
        }
        return out;
    }

    /** Path tokens preserved, environment references expanded, anything the environment answered hashed. */
    private static String keyed(String raw, String where, Mode.CacheKey mode) {
        boolean[] fromEnvironment = {false};
        String expanded = Interpolation.expand(raw, where, name -> switch (name) {
            case TARGET, MODULE -> "${" + name + "}";
            default -> {
                String value = mode.lookup().get(name);
                if (value != null) fromEnvironment[0] = true;
                yield value;
            }
        });
        String masked = mode.secrets().forCacheKey(expanded);
        // A non-secret reference (${HOME}, a CI build id) still keys by VALUE — a changed
        // environment must retest — but the literal is an absolute path or an identifier and must
        // not land in a key that may be shared between machines.
        if (fromEnvironment[0] && masked.equals(expanded)) {
            return SecretRedactor.KEY_PREFIX + Hashing.sha256Hex(expanded);
        }
        return masked;
    }

    private static String absolute(Path path, String token) {
        Objects.requireNonNull(path, token);
        return path.toAbsolutePath().toString();
    }
}
