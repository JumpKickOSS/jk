// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.ManifestBuild.scalar;

import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/** The manifest's environment tables: {@code [env]} and {@code [test] env}. */
@NullMarked
final class ManifestEnv {

    private ManifestEnv() {}

    /**
     * {@code [test] env} — environment for each forked test JVM, an array whose element shape says
     * which of the two statements it is.
     *
     * <pre>{@code
     * env = [
     *   "JK_WEB_JS_SKIP",                       # forward the caller's, if set
     *   { TZ = "UTC", LANG = "C" },             # set these outright
     * ]
     * }</pre>
     *
     * <p>Values are stored exactly as written; {@code ${target}}, {@code ${module}} and {@code
     * ${VAR}} are expanded later by {@link TestEnvValues}, because the forked JVM wants real values
     * while the run-tests cache key wants portable tokens. This is one of the few positions where
     * {@code ${VAR}} is legal at all — see {@link Interpolation}.
     *
     * <p>A bare string is a name and nothing else: it may not carry {@code =} or {@code ${…}}.
     * Both are rejected rather than interpreted, because both are how the same idea is spelled in
     * the two neighbouring formats a reader is likely arriving from — a {@code .env} file and a
     * {@code docker run -e} flag — and quietly accepting either would make {@code "TZ=UTC"} a
     * variable literally named {@code TZ=UTC}.
     */
    static List<EnvDecl> parseTestEnv(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return List.of();
        return parseEnvDecls(test.get(List.of("env")), "[test]", "env");
    }

    /** The keys {@code [env]} may carry. */
    public static final List<String> ENV_KEYS = List.of("require", "optional", "vars", "inherit");

    /**
     * {@code [env]} — what this module's workers may take from the environment beyond jk's
     * allow-list (docs/user/build.md). Unknown keys fail the parse: a variable written straight into
     * the table is refused with the {@code vars} spelling, so a typo cannot silently become a knob.
     *
     * <pre>
     * [env]
     * require = ["MY_TOKEN"]      # from the shell running jk; unset fails the module before it compiles
     * optional = ["FEATURE_X"]    # from the shell when it sets it
     * vars = [{ TZ = "UTC" }]     # literal values
     * inherit = true              # the caller's whole environment; say why beside it
     * </pre>
     */
    static EnvConfig parseEnv(TomlTable root) {
        Object raw = root.get(List.of("env"));
        if (raw == null) return EnvConfig.EMPTY;
        if (!(raw instanceof TomlTable env)) {
            throw new JkBuildParseException("[env] must be a table: [env] require = [\"MY_TOKEN\"]");
        }
        for (String key : env.keySet()) {
            if (!ENV_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[env] unknown key `" + key + "`; to set a variable use vars = [{ " + key + " = \"…\" }]");
            }
        }
        boolean inherit = false;
        Object rawInherit = env.get(List.of("inherit"));
        if (rawInherit != null) {
            if (!(rawInherit instanceof Boolean b)) {
                throw new JkBuildParseException("[env] inherit must be true or false");
            }
            inherit = b;
        }
        Map<String, Integer> lines = new LinkedHashMap<>();
        List<String> require = envNames(env, "require", lines);
        List<String> optional = envNames(env, "optional", lines);
        List<EnvDecl.Set> vars = new ArrayList<>();
        for (EnvDecl decl : parseEnvDecls(env.get(List.of("vars")), "[env]", "vars")) {
            switch (decl) {
                case EnvDecl.Set set -> {
                    if (lines.containsKey(set.name())) {
                        throw new JkBuildParseException("[env] " + set.name()
                                + " is declared and also set in vars; keep the declaration or the value");
                    }
                    vars.add(set);
                }
                case EnvDecl.Forward forward ->
                    throw new JkBuildParseException("[env] vars: \"" + forward.name()
                            + "\" is a name, not a value; declare it with optional = [\"" + forward.name()
                            + "\"] (or require)");
            }
        }
        return new EnvConfig(inherit, require, optional, vars, lines);
    }

    /**
     * {@code [env] require} or {@code optional}: an array of names, each placed on its manifest line.
     * A name may be declared once across the two.
     */
    private static List<String> envNames(TomlTable env, String key, Map<String, Integer> lines) {
        Object raw = env.get(List.of(key));
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new JkBuildParseException(
                    "[env] " + key + " must be an array of names, e.g. " + key + " = [\"MY_TOKEN\"]");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String where = "[env]." + key + "[" + i + "]";
            if (!(arr.get(i) instanceof String rawName)) {
                throw new JkBuildParseException(where + " must be a variable name");
            }
            String name = forwardName(rawName, where);
            if (lines.containsKey(name)) {
                throw new JkBuildParseException("[env] " + name + " is declared twice; keep one declaration");
            }
            out.add(name);
            TomlPosition at = arr.inputPositionOf(i);
            lines.put(name, at == null ? 0 : at.line());
        }
        return List.copyOf(out);
    }

    /**
     * The one parser for an environment array — {@code [test] env} and {@code [env] vars} share
     * the shape: a bare name forwards, a table sets. {@code table} and {@code key} only spell the
     * position in messages.
     */
    private static List<EnvDecl> parseEnvDecls(@Nullable Object raw, String table, String key) {
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new JkBuildParseException(
                    table + " " + key + " must be an array, e.g. " + key + " = [\"CI\", { TZ = \"UTC\" }]");
        }
        List<EnvDecl> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            String where = table + "." + key + "[" + i + "]";
            if (element instanceof String name) {
                out.add(new EnvDecl.Forward(forwardName(name, where)));
            } else if (element instanceof TomlTable values) {
                for (String name : values.keySet()) {
                    out.add(new EnvDecl.Set(name, scalar(values.get(List.of(name)), where + "." + name)));
                }
            } else {
                throw new JkBuildParseException(where + " must be a variable name or a table of values");
            }
        }
        return List.copyOf(out);
    }

    /** A forwarded name is a name: no value half, no reference. */
    private static String forwardName(String raw, String where) {
        String name = raw.trim();
        if (name.isEmpty()) throw new JkBuildParseException(where + " is an empty environment variable name");
        if (name.indexOf('=') >= 0) {
            throw new JkBuildParseException(where + ": \"" + raw + "\" is not a name; to set a value write { "
                    + name.substring(0, name.indexOf('=')) + " = \""
                    + name.substring(name.indexOf('=') + 1) + "\" }");
        }
        if (name.indexOf('$') >= 0) {
            throw new JkBuildParseException(where + ": \"" + raw + "\" is not a name; ${…} works only in a table");
        }
        return name;
    }
}
