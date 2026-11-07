// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Where {@code ${VAR}} may appear in a {@code jk.toml}, and what happens where it may not.
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
 * <li>{@code [repositories.<name>]} credentials — {@code username}, {@code password}, {@code token}
 * <li>{@code [repositories.<name>]} object-store keys — {@code region}, {@code endpoint},
 * {@code access-key}, {@code secret-key}, {@code session-token}
 * <li>{@code [test] env} values — what a forked test JVM sees
 * </ul>
 *
 * <h2>Not allowed, and why</h2>
 *
 * Versions, coordinates, source roots, {@code extra-src}, compiler args,
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

    /** A key TOML lets stand unquoted in a dotted path. */
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    /** A segment as {@link #indexed} spells it: the key, then the subscripts {@link #walkArray} added. */
    private static final Pattern SUBSCRIPTED = Pattern.compile("(.*?)((?:\\[\\d+])*)", Pattern.DOTALL);

    /**
     * Dotted paths where a reference is honoured. {@code *} matches one whole path segment;
     * {@code name[*]} matches one array element of {@code name}, which a bare {@code *} deliberately
     * never does — an array index is not a slot anyone whitelisted by name.
     */
    private static final List<String> ALLOWED = List.of(
            "repositories.*.username",
            "repositories.*.password",
            "repositories.*.token",
            "repositories.*.region",
            "repositories.*.endpoint",
            "repositories.*.access-key",
            "repositories.*.secret-key",
            "repositories.*.session-token",
            // [test] env is an array: a value lives in a table inside it, at test.env[i].NAME.
            // Note what is NOT here — test.env[*], the bare-name position. A forwarded name is a
            // name, so `env = ["${FOO}"]` stays an error, and the split between "names are
            // literal, values may expand" is enforced by this list rather than by the parser
            // remembering to.
            "test.env[*].*",
            // A generator entry's arguments carry the generator worker's own vocabulary — ${in},
            // ${inputs}, ${unpacked}, ${out}, ${module.dir} — and a tool's own placeholders, none
            // of them an environment variable; the worker expands them, the manifest never does.
            "generate.*.args[*]");

    /**
     * The positions where a {@code ${file:...}} reference may stand in a project manifest — a
     * repository's credential and object-store keys — so the credential resolver can refuse it with
     * its once-per-run warning and fall through to the name-keyed sources. Anywhere else it is an
     * error at parse time.
     */
    private static final List<String> MACHINE_REFERENCE_POSITIONS =
            ALLOWED.stream().filter(p -> p.startsWith("repositories.")).toList();

    private Interpolation() {}

    /**
     * Reject {@code ${VAR}} outside {@link #ALLOWED}, a {@code ${file:...}} reference outside a
     * repository's credential and object-store keys, and a {@code ${cmd:...}} reference anywhere — a
     * project that could name a command would run it on every resolve — naming the offending
     * position.
     *
     * <p>Called once per parse. Cheap: it only walks string values, and only those containing
     * {@code $}.
     */
    public static void guard(TomlTable root) {
        Offenders found = new Offenders(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        walk(root, List.of(), found);
        if (!found.commands().isEmpty()) {
            throw new JkBuildParseException("${cmd:} is not allowed in jk.toml: " + String.join(", ", found.commands())
                    + "; declare the repository in ~/.jk/config.toml");
        }
        List<String> machine = found.machine();
        if (!machine.isEmpty()) {
            throw new JkBuildParseException("${file:} is not allowed here: " + String.join(", ", machine)
                    + "; use it only in ~/.jk/config.toml");
        }
        List<String> offenders = found.env();
        if (offenders.isEmpty()) return;
        // A [test] env written as one table is a shape error first: the reference would be
        // honoured in the array it should have been.
        if (root.get(List.of("test", "env")) instanceof TomlTable) {
            throw new JkBuildParseException("[test] env must be an array, e.g. env = [\"CI\", { TZ = \"UTC\" }]");
        }
        throw new JkBuildParseException("${VAR} is not allowed here: " + String.join(", ", offenders)
                + "; use it only in credentials, [generate] args or [test] env");
    }

    /** Positions holding a reference they may not: environment ones, file ones, and every command. */
    private record Offenders(List<String> env, List<String> machine, List<String> commands) {}

    private static void walk(TomlTable table, List<String> prefix, Offenders offenders) {
        for (String key : table.keySet()) {
            Object value = table.get(List.of(key));
            List<String> path = append(prefix, key);
            if (value instanceof TomlTable nested) {
                walk(nested, path, offenders);
            } else if (value instanceof TomlArray array) {
                walkArray(array, path, offenders);
            } else if (value instanceof String s) {
                check(s, path, offenders);
            }
        }
    }

    private static void walkArray(TomlArray array, List<String> path, Offenders offenders) {
        for (int i = 0; i < array.size(); i++) {
            Object element = array.get(i);
            List<String> indexed = indexed(path, i);
            if (element instanceof TomlTable nested) {
                walk(nested, indexed, offenders);
            } else if (element instanceof TomlArray nested) {
                walkArray(nested, indexed, offenders);
            } else if (element instanceof String s) {
                check(s, indexed, offenders);
            }
        }
    }

    private static List<String> append(List<String> prefix, String key) {
        List<String> out = new ArrayList<>(prefix.size() + 1);
        out.addAll(prefix);
        out.add(key);
        return out;
    }

    /** {@code path} with its last segment subscripted: {@code env} becomes {@code env[3]}. */
    private static List<String> indexed(List<String> path, int i) {
        List<String> out = new ArrayList<>(path);
        out.set(out.size() - 1, out.getLast() + "[" + i + "]");
        return out;
    }

    /**
     * The environment variable names {@code value} references — {@code ${NAME}} and {@code
     * ${env:NAME}} — in order and without duplicates. {@link ConfigReference#all} has every scheme.
     *
     * <p>Public because the reference syntax has a second, distant consumer: the client has to know
     * which variables a manifest reads before it can resolve any of them, and a second copy of this
     * pattern is a second answer to "what is a reference" that nothing would keep in step.
     */
    public static Set<String> references(@Nullable String value) {
        Set<String> found = new LinkedHashSet<>();
        for (ConfigReference ref : ConfigReference.all(value)) {
            if (ref.isEnv()) found.add(ref.body());
        }
        return found;
    }

    private static void check(String value, List<String> path, Offenders offenders) {
        List<ConfigReference> found = ConfigReference.all(value);
        if (found.isEmpty()) return;
        boolean envAllowed = allowed(path);
        boolean machineAllowed = allowed(path, MACHINE_REFERENCE_POSITIONS);
        for (ConfigReference ref : found) {
            if (ref.scheme() == ConfigReference.Scheme.CMD) {
                offenders.commands().add(spell(path) + " (" + ref.spelling() + ")");
            } else if (ref.isEnv()) {
                if (!envAllowed) offenders.env().add(spell(path) + " (" + ref.spelling() + ")");
            } else if (!machineAllowed) {
                offenders.machine().add(spell(path) + " (" + ref.spelling() + ")");
            }
        }
    }

    /**
     * {@code path} as the manifest spells it. A segment TOML lets stand bare stands bare; any
     * other is a quoted key, so a repository named {@code nexus.internal} reads back as
     * {@code repositories."nexus.internal".url} — one table, exactly as written — and not as the
     * three-level {@code repositories.nexus.internal.url} the same dots would mean unquoted. An
     * array subscript is jk's own notation, not a key, so it stays outside the quotes.
     */
    static String spell(List<String> path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path) {
            if (!out.isEmpty()) out.append('.');
            Matcher m = SUBSCRIPTED.matcher(segment);
            if (!m.matches()) throw new IllegalStateException("unreachable: " + segment);
            String key = m.group(1);
            out.append(BARE_KEY.matcher(key).matches() ? key : quoted(key)).append(m.group(2));
        }
        return out.toString();
    }

    /** {@code key} as a TOML basic string. */
    private static String quoted(String key) {
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * True when {@code path} — one element per table key, so a quoted key such as
     * {@code "nexus.internal"} is a single segment however many dots it carries — matches an
     * allowed pattern; array indices never match.
     */
    static boolean allowed(List<String> path) {
        return allowed(path, ALLOWED);
    }

    private static boolean allowed(List<String> path, List<String> patterns) {
        for (String pattern : patterns) {
            if (matches(pattern.split("\\."), path)) return true;
        }
        return false;
    }

    /** {@link #allowed(List)} for a dotted spelling whose keys carry no dots of their own. */
    static boolean allowed(String path) {
        return allowed(List.of(path.split("\\.")));
    }

    private static boolean matches(String[] p, List<String> a) {
        if (p.length != a.size()) return false;
        for (int i = 0; i < p.length; i++) {
            String actual = a.get(i);
            if (p[i].equals("*")) {
                if (actual.indexOf('[') >= 0) return false; // an array element is never a whitelisted slot
                continue;
            }
            if (p[i].endsWith("[*]") && indexedMatch(p[i], actual)) continue;
            if (!p[i].equals(actual)) return false;
        }
        return true;
    }

    /** {@code env[*]} against {@code env[3]}: same name, and the subscript is a plain index. */
    /**
     * Why {@code ${VAR}} could not be resolved, and what to do about it.
     *
     * <p>The bare fact — "unset environment variable" — is the one thing the reader already knows;
     * they exported it. What they cannot see is that {@code jk} is a client talking to a daemon that
     * has its own environment, so exporting a variable is not the same as the build seeing one. The
     * message says which of the two halves is missing, and both fixes, because from inside the
     * engine the two are indistinguishable: the variable really is unset, whether that is because
     * the caller never set it or because nothing declared it and so nothing carried it across.
     */
    static String unsetMessage(String describe, String var) {
        return describe + " references unset ${" + var + "}; set it and list it in [test] env = [\"" + var + "\"]";
    }

    private static boolean indexedMatch(String pattern, String actual) {
        String name = pattern.substring(0, pattern.length() - "[*]".length());
        if (!actual.startsWith(name + "[") || !actual.endsWith("]")) return false;
        String index = actual.substring(name.length() + 1, actual.length() - 1);
        if (index.isEmpty()) return false;
        for (int i = 0; i < index.length(); i++) {
            if (!Character.isDigit(index.charAt(i))) return false;
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
    public static @Nullable String expand(
            @Nullable String raw, String describe, Function<String, @Nullable String> env) {
        if (raw == null || raw.indexOf('$') < 0) return raw;
        return RepositoryToml.interpolate(raw, var -> {
            String value = env.apply(var);
            if (value == null) throw new JkBuildParseException(unsetMessage(describe, var));
            return value;
        });
    }
}
