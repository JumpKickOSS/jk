// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Jlink;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.tomlj.TomlTable;

/** {@code [jlink]}: present (even empty) means the module packages a custom runtime image. */
final class ManifestJlink {

    static final String TABLE = "jlink";

    static final String MODULES = "modules";
    static final String STRIP_DEBUG = "strip-debug";
    static final String AOT_CACHE = "aot-cache";
    static final String JVM_ARGS = "jvm-args";
    static final String ARGS = "args";
    static final String START_TIMEOUT = "start-timeout";
    static final String TEST_START = "test-start";
    static final String TRAINING_ENV = "training-env";

    static final List<String> KEYS =
            List.of(MODULES, STRIP_DEBUG, AOT_CACHE, JVM_ARGS, ARGS, START_TIMEOUT, TEST_START, TRAINING_ENV);

    /** A JDK module name: dotted Java identifiers. */
    private static final Pattern MODULE_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    private ManifestJlink() {}

    static Optional<Jlink> parse(TomlTable root) {
        if (root.contains(TABLE) && !root.isTable(TABLE)) {
            throw new JkBuildParseException("`jlink` must be a table: write [jlink], optionally with its keys");
        }
        TomlTable table = root.getTable(TABLE);
        if (table == null) return Optional.empty();
        for (String key : table.keySet()) {
            if (!KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[jlink] unknown key `" + key + "` — expected one of: " + String.join(", ", KEYS));
            }
        }
        List<String> modules = JkBuildParser.optionalStringList(table, MODULES, "jlink." + MODULES);
        for (String module : modules) {
            if (!MODULE_NAME.matcher(module).matches()) {
                throw new JkBuildParseException(
                        "[jlink] modules are JDK module names, such as \"jdk.crypto.ec\"; got \"" + module + "\"");
            }
        }
        boolean stripDebug = bool(table, STRIP_DEBUG, true);
        Jlink.AotCache aot = aotCache(table);
        boolean testStart = bool(table, TEST_START, true);
        if (!testStart && aot != Jlink.AotCache.OFF) {
            throw new JkBuildParseException("[jlink] test-start = false leaves nothing to train the startup cache with;"
                    + " add aot-cache = false as well");
        }
        int timeout = Jlink.DEFAULT_START_TIMEOUT;
        if (table.contains(START_TIMEOUT)) {
            Long seconds = table.isLong(START_TIMEOUT) ? table.getLong(START_TIMEOUT) : null;
            if (seconds == null || seconds <= 0 || seconds > 3600) {
                throw new JkBuildParseException("[jlink] start-timeout is a number of seconds between 1 and 3600");
            }
            timeout = seconds.intValue();
        }
        return Optional.of(new Jlink(
                modules,
                stripDebug,
                aot,
                JkBuildParser.optionalStringList(table, JVM_ARGS, "jlink." + JVM_ARGS),
                JkBuildParser.optionalStringList(table, ARGS, "jlink." + ARGS),
                timeout,
                testStart,
                trainingEnv(table)));
    }

    private static boolean bool(TomlTable table, String key, boolean fallback) {
        if (!table.contains(key)) return fallback;
        if (!table.isBoolean(key)) throw new JkBuildParseException("[jlink] " + key + " must be true or false");
        return Boolean.TRUE.equals(table.getBoolean(key));
    }

    /** {@code true} (the default), {@code false}, or {@code "cds"}. */
    private static Jlink.AotCache aotCache(TomlTable table) {
        if (!table.contains(AOT_CACHE)) return Jlink.AotCache.AUTO;
        if (table.isBoolean(AOT_CACHE)) {
            return Boolean.TRUE.equals(table.getBoolean(AOT_CACHE)) ? Jlink.AotCache.AUTO : Jlink.AotCache.OFF;
        }
        if ("cds".equals(table.getString(AOT_CACHE))) return Jlink.AotCache.CDS;
        throw new JkBuildParseException("[jlink] aot-cache is true (an AOT cache on JDK 25+, a CDS archive below),"
                + " false, or \"cds\" (a CDS archive on any JDK)");
    }

    private static Map<String, String> trainingEnv(TomlTable table) {
        if (!table.contains(TRAINING_ENV)) return Map.of();
        if (!table.isTable(TRAINING_ENV)) {
            throw new JkBuildParseException(
                    "[jlink] training-env is a table of variables: [jlink.training-env] NAME = \"value\"");
        }
        TomlTable env = table.getTable(TRAINING_ENV);
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) return out;
        for (String name : env.keySet()) {
            Object value = env.get(List.of(name));
            if (!(value instanceof String) && !(value instanceof Long) && !(value instanceof Boolean)) {
                throw new JkBuildParseException("[jlink.training-env] " + name + " must be a string");
            }
            out.put(name, String.valueOf(value));
        }
        return out;
    }
}
