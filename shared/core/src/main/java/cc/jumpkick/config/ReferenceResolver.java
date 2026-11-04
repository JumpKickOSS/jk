// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * Expands every {@link ConfigReference} in a value, under the rule of the file that wrote it.
 *
 * <p>Which file wrote a value decides which schemes it may use. The user's own {@code
 * ~/.jk/config.toml} may use them all: it describes this machine, and nobody else can edit it. A
 * project manifest is anyone's, so it may read only environment variables, and the credential
 * resolver narrows that further to the repository's own {@code JK_REPO_<ID>_*} names. A {@code
 * file:} or {@code cmd:} reference reached here from a project manifest is a bug upstream of this
 * class, so it is an error rather than a read or a run.
 *
 * <p>Strict: an unset variable, a missing file or a missing key is an error that starts with {@code
 * where}, never an empty string. A value read from a file is filed with {@link ResolvedSecrets} so
 * the redactor masks it wherever it travels; an environment value is filed by the credential
 * resolver that asked for it, as before.
 */
public final class ReferenceResolver {

    /** Which file declared the value being expanded. */
    public enum Origin {
        /** {@code ~/.jk/config.toml}: every scheme. */
        USER_CONFIG,
        /** A project {@code jk.toml}: environment references only. */
        PROJECT
    }

    private final Function<String, String> env;
    private final Supplier<Path> baseDir;

    /**
     * @param env an environment variable's value, or a throw naming the position when it is unset —
     *     each caller keeps its own unset message
     * @param baseDir the directory a relative {@code file:} path is resolved against: the declaring
     *     file's own
     */
    public ReferenceResolver(Function<String, String> env, Supplier<Path> baseDir) {
        this.env = Objects.requireNonNull(env, "env");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    /** A resolver for values {@code ~/.jk/config.toml} declares: relative paths are under its directory. */
    public static ReferenceResolver forUserConfig(Function<String, String> env) {
        return new ReferenceResolver(env, ReferenceResolver::userConfigDir);
    }

    /** As {@link #forUserConfig} with the strict unset-variable message naming {@code where}. */
    public static ReferenceResolver forUserConfig(Function<String, @Nullable String> env, String where) {
        return forUserConfig(strict(env, where));
    }

    /** {@code env} as a lookup that throws, naming {@code where}, for an unset variable. */
    public static Function<String, String> strict(Function<String, @Nullable String> env, String where) {
        return var -> {
            String value = env.apply(var);
            if (value == null) {
                throw new JkBuildParseException(where + " references unset environment variable ${" + var + "}");
            }
            return value;
        };
    }

    /** The directory of {@code ~/.jk/config.toml}. */
    public static Path userConfigDir() {
        Path file = JkDirs.userConfigFile().toAbsolutePath();
        Path parent = file.getParent();
        return parent != null ? parent : file;
    }

    /**
     * {@code raw} with every reference expanded; {@code null} in, {@code null} out. {@code where}
     * names the position ({@code repositories.<id>.<key>}) in every error.
     */
    public @Nullable String expand(@Nullable String raw, String where, Origin origin) {
        if (raw == null || raw.indexOf('$') < 0) return raw;
        Matcher m = ConfigReference.PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = value(ConfigReference.of(m), where, origin);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String value(ConfigReference ref, String where, Origin origin) {
        if (ref.isEnv()) return env.apply(ref.body());
        if (origin != Origin.USER_CONFIG) throw notHere(ref, where);
        if (ref.scheme() == ConfigReference.Scheme.CMD) return CredentialHelpers.value(ref.body(), where);
        String value = SecretFiles.reference(ref.body(), baseDir.get(), where);
        ResolvedSecrets.record(value);
        return value;
    }

    /** The error for a scheme a project manifest may not use. */
    static JkBuildParseException notHere(ConfigReference ref, String where) {
        return new JkBuildParseException(
                where + " uses " + ref.spelling() + ": a ${" + ref.scheme().prefix()
                        + ":} reference is honoured only in ~/.jk/config.toml, which describes this machine;"
                        + (ref.scheme() == ConfigReference.Scheme.CMD
                                ? " a project manifest may not run a command on this machine"
                                : " a project manifest may not read this machine's files"));
    }
}
