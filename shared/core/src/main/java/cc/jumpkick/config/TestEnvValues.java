// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * What a forwarded-but-unset variable contributes to an action key. Not the empty string, which
     * is a value a caller can genuinely have.
     */
    private static final String ABSENT = "\u0000absent";

    /**
     * True for {@code ${target}} / {@code ${module}} — jk's own tokens, not environment variables.
     *
     * <p>Asked by anything deciding what a manifest needs from the environment: these two resolve
     * from the layout and must not be sought in a shell, or a build would demand a variable named
     * {@code target}.
     */
    public static boolean isPathToken(String name) {
        return TARGET.equals(name) || MODULE.equals(name);
    }

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
     * <p>Order is the manifest's: later entries win, and a {@link JkBuild.TestEnvDecl.Forward} of a
     * variable the caller does not have <em>removes</em> what an earlier entry set, so a manifest
     * reads top to bottom with no precedence rule to memorise.
     *
     * @throws JkBuildParseException if a {@link JkBuild.TestEnvDecl.Set} value references an
     * environment variable that is not set — in both modes, which is the whole point of this type
     */
    public static Map<String, String> resolve(
            List<JkBuild.TestEnvDecl> declared, Path moduleDir, Path target, Mode mode) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JkBuild.TestEnvDecl decl : declared) {
            String where = "[test].env." + decl.name();
            switch (decl) {
                case JkBuild.TestEnvDecl.Forward forward -> {
                    String value = forwarded(forward.name(), mode);
                    // Absent, not empty: a suite testing getenv(X) != null has to see what it would
                    // see outside jk. Remove, because an earlier entry may have set it and a later
                    // forward of something the caller does not have must not resurrect that value.
                    if (value == null) out.remove(forward.name());
                    else out.put(forward.name(), value);
                }
                case JkBuild.TestEnvDecl.Set set ->
                    out.put(
                            set.name(),
                            switch (mode) {
                                case Mode.Launch launch ->
                                    Interpolation.expand(set.value(), where, name -> switch (name) {
                                        case TARGET -> absolute(target, TARGET);
                                        case MODULE -> absolute(moduleDir, MODULE);
                                        default -> launch.env().apply(name);
                                    });
                                case Mode.CacheKey key -> keyed(set.value(), where, key);
                            });
            }
        }
        return out;
    }

    /**
     * A forwarded name in each mode. {@link Mode.Launch} wants the caller's actual value or nothing;
     * {@link Mode.CacheKey} wants something that differs when the value differs and when it appears
     * or disappears — hence a marker for absent rather than dropping the entry, so that toggling
     * {@code JK_WEB_JS_SKIP} cannot replay the other setting's cached result.
     */
    private static String forwarded(String name, Mode mode) {
        return switch (mode) {
            case Mode.Launch launch -> launch.env().apply(name);
            case Mode.CacheKey key -> {
                String value = key.lookup().get(name);
                if (value == null) yield ABSENT;
                String masked = key.secrets().forCacheKey(value);
                // Hashed, never written out: a forwarded value is by definition the machine's, and
                // an action key may be read on another one.
                yield masked.equals(value) ? SecretRedactor.KEY_PREFIX + Hashing.sha256Hex(value) : masked;
            }
        };
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
