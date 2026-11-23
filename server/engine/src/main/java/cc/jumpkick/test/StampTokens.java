// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyAudit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The named tokens of the key a module's last green suite run was stored under, kept beside what
 * its suites were found to read, so a forecast whose key misses can say which input moved instead
 * of a bare "run tests". Written once per real green run and read only on a miss: a replay neither
 * writes nor reads it.
 */
public final class StampTokens {

    private StampTokens() {}

    /**
     * The reason for a key whose jk moved, when which part did is not known: its version, its
     * cache-key salt, or the engine code that runs a suite ({@link cc.jumpkick.task.KeyContract#SUITE}).
     * A suite's stamp names the part ({@link #jkChanged}).
     */
    public static final String JK_CHANGED = "jk changed";

    /** The extras token every stamp carries for the jk that took it ({@link cc.jumpkick.task.KeyContract#keyVersion}). */
    private static final String JK_TOKEN = "x:jk:";

    /** Where the tokens of the module's last green run under {@code selection} are kept. */
    static Path file(Path moduleDir, TestSelection selection) {
        Path discovered = SuiteReads.discoveredFile(moduleDir, selection);
        return discovered.resolveSibling(discovered.getFileName().toString().replace("test-inputs-", "stamp-tokens-"));
    }

    /**
     * Keep the tokens of {@code stampKey} as {@code source} derives it now, when it still derives
     * that key: a green marker was just stored under it. Best-effort.
     */
    public static void keep(Path moduleDir, TestSelection selection, String stampKey, KeyAudit.KeySource source) {
        try {
            KeyAudit.Keyed now = KeyAudit.tapped(source);
            if (now == null || !now.key().equals(stampKey)) return;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> t : now.tokens().entrySet()) {
                sb.append(t.getKey().replace('\n', ' '))
                        .append('\t')
                        .append(t.getValue().replace('\n', ' '))
                        .append('\n');
            }
            Path file = file(moduleDir, selection);
            Files.createDirectories(file.getParent());
            AtomicWrites.replaceIfChanged(file, sb.toString());
        } catch (Exception unkept) {
            Log.debug("stamp tokens: not kept for " + moduleDir, unkept);
        }
    }

    /** The tokens kept for the module's last green run under {@code selection}; null when none are. */
    static @Nullable Map<String, String> load(Path moduleDir, TestSelection selection) {
        Path file = file(moduleDir, selection);
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
            }
            return out;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The reason when the module has no green run on record under the selection: never run, or a cache cleaned. */
    public static final String NO_GREEN_RUN = "no green run on record";

    /**
     * The reason when the tokens of the last green run and the tokens now agree, yet the key misses:
     * the key reads something its tokens do not name, which is a jk bug worth reporting.
     */
    public static final String KEY_MOVED_UNNAMED = "its key moved with no input named";

    /** The reason when the suite's key cannot be taken now. */
    public static final String KEY_UNREAD = "an input of its key does not read";

    /**
     * Why the suite's key, as {@code source} derives it now, is not the key of its last green run:
     * {@link #jkChanged} when the jk that keys it moved, else the first input that moved and how
     * many more did; never nothing, so a forecast that runs a suite always says why: {@link
     * #NO_GREEN_RUN}, {@link #KEY_MOVED_UNNAMED} or {@link #KEY_UNREAD}.
     */
    public static String reason(Path moduleDir, TestSelection selection, KeyAudit.KeySource source) {
        Map<String, String> was = load(moduleDir, selection);
        if (was == null) return NO_GREEN_RUN;
        KeyAudit.Keyed now;
        try {
            now = KeyAudit.tapped(source);
        } catch (Exception unkeyed) {
            Log.debug("stamp tokens: no key to compare for " + moduleDir, unkeyed);
            return KEY_UNREAD;
        }
        if (now == null) return KEY_UNREAD;
        String moved = reason(was, now.tokens());
        return moved != null ? moved : KEY_MOVED_UNNAMED;
    }

    /**
     * {@link #jkChanged} when the module's last green run under {@code selection} was keyed by
     * another jk than {@code keyVersion} names, else null: a forecast that cannot take the suite's key
     * yet (its compile is dirty) still says the jk moved, which is why every step of the tree reruns.
     */
    public static @Nullable String jkMoved(Path moduleDir, TestSelection selection, String keyVersion) {
        Map<String, String> was = load(moduleDir, selection);
        if (was == null) return null;
        for (String name : was.keySet()) {
            if (name.startsWith(JK_TOKEN)) {
                String now = JK_TOKEN + keyVersion;
                return name.equals(now) ? null : jkChanged(name, now);
            }
        }
        return null;
    }

    /**
     * Which parts of the jk a suite is keyed by moved between the tokens {@code was} and {@code now},
     * each {@code x:jk:<version>#<salt>+<suite code digest>} ({@link
     * cc.jumpkick.task.KeyContract#keyVersion}): {@code jk changed (version)}, {@code (key format)}
     * for the salt, {@code (suite code)}, or several of them; a bare {@link #JK_CHANGED} when either
     * token is spelled otherwise.
     */
    static String jkChanged(String was, String now) {
        String[] a = jkParts(was);
        String[] b = jkParts(now);
        if (a == null || b == null) return JK_CHANGED;
        List<String> moved = new ArrayList<>();
        for (int i = 0; i < JK_PARTS.size(); i++) {
            if (!a[i].equals(b[i])) moved.add(JK_PARTS.get(i));
        }
        return moved.isEmpty() ? JK_CHANGED : JK_CHANGED + " (" + String.join(", ", moved) + ")";
    }

    /** The parts of a jk token as a reason names them, in the order {@link #jkParts} splits it. */
    private static final List<String> JK_PARTS = List.of("version", "key format", "suite code");

    /** A jk token's version, salt and suite code digest; null when it is not spelled so. */
    private static String @Nullable [] jkParts(String token) {
        if (!token.startsWith(JK_TOKEN)) return null;
        String v = token.substring(JK_TOKEN.length());
        int plus = v.lastIndexOf('+');
        int hash = plus < 0 ? -1 : v.lastIndexOf('#', plus);
        if (hash <= 0) return null;
        return new String[] {v.substring(0, hash), v.substring(hash + 1, plus), v.substring(plus + 1)};
    }

    /** As {@link #reason(Path, TestSelection, KeyAudit.KeySource)} over the two token maps. */
    static @Nullable String reason(Map<String, String> was, Map<String, String> now) {
        List<KeyAudit.Token> moved = KeyAudit.diff(was, now);
        if (moved.isEmpty()) return null;
        for (KeyAudit.Token t : moved) {
            if (t.name().startsWith(JK_TOKEN)) return jkChanged(jkToken(was), jkToken(now));
        }
        // The caller's variables by name, never by value: which one changed, came or went.
        String env = variablesMoved(moved);
        if (env != null) {
            long rest = moved.stream().filter(t -> !envToken(t.name())).count();
            return rest == 0 ? env : env + " (+" + rest + " more)";
        }
        String name = moved.getFirst().name();
        String first = describe(name);
        String reason = moved.size() == 1 ? first : first + " (+" + (moved.size() - 1) + " more)";
        // A file the suite was found to read keys the whole selection, wherever in the tree it is:
        // say which suites it keys, so a repo-scanning test's reach is legible.
        if (name.startsWith("x:read:") || name.startsWith("x:list:")) {
            String suites = suitesRun(now);
            if (suites != null) reason += " · keys suites " + suites;
        }
        return reason;
    }

    /** The jk token among {@code tokens}, or an empty name when there is none. */
    private static String jkToken(Map<String, String> tokens) {
        for (String name : tokens.keySet()) {
            if (name.startsWith(JK_TOKEN)) return name;
        }
        return "";
    }

    /** How many variables a reason names before it counts the rest. */
    private static final int VARIABLES_NAMED = 3;

    private static final String ENV_READ = "x:env-read:";
    private static final String ENV_NAMES = "x:env-names";
    private static final String ENV_ALL = "x:env-all";

    /** Whether {@code name} is a token of the caller's environment ({@link SuiteEnvReads}). */
    private static boolean envToken(String name) {
        return name.startsWith(ENV_READ)
                || name.startsWith(ENV_NAMES)
                || name.startsWith(ENV_ALL)
                || name.equals(ActionRecordText.HOST_KEYED_INPUT)
                || name.startsWith(SuiteEnvReads.NAME_DETAIL)
                || name.startsWith(SuiteEnvReads.VALUE_DETAIL);
    }

    /**
     * The variables of the caller's environment among {@code moved}, each named once with what it
     * did: {@code MUST_BE_TRUE changed}, {@code FOO set} (it was unset, or not among the names),
     * {@code BAR unset}; at most {@link #VARIABLES_NAMED}, then how many more. A listing or whole
     * environment kept without its names (a run with no caller) says only that the names or the
     * values changed. Null when no such token moved. Never a value.
     */
    static @Nullable String variablesMoved(List<KeyAudit.Token> moved) {
        Map<String, String> said = new TreeMap<>();
        String aggregate = null;
        for (KeyAudit.Token t : moved) {
            String n = t.name();
            String variable;
            if (n.startsWith(ENV_READ)) variable = n.substring(ENV_READ.length());
            else if (n.startsWith(SuiteEnvReads.NAME_DETAIL))
                variable = n.substring(SuiteEnvReads.NAME_DETAIL.length());
            else if (n.startsWith(SuiteEnvReads.VALUE_DETAIL))
                variable = n.substring(SuiteEnvReads.VALUE_DETAIL.length());
            else {
                if (aggregate == null && (n.startsWith(ENV_NAMES) || n.startsWith(ENV_ALL))) aggregate = describe(n);
                continue;
            }
            said.putIfAbsent(variable, variable + " " + did(n.startsWith(ENV_READ), t));
        }
        if (said.isEmpty()) return aggregate;
        List<String> named = new ArrayList<>(said.values());
        String head = String.join(", ", named.subList(0, Math.min(VARIABLES_NAMED, named.size())));
        int more = named.size() - VARIABLES_NAMED;
        return more > 0 ? head + " and " + more + " more variable" + (more == 1 ? "" : "s") : head;
    }

    /**
     * What one variable did between the two keys: a value read by name keys {@code unset} when the
     * shell had none; a name behind a listing or a whole environment is there or not.
     */
    private static String did(boolean readByName, KeyAudit.Token t) {
        if (readByName) {
            if (t.was() == null) return "newly read";
            if (t.now() == null) return "no longer read";
            if (UNSET.equals(t.was())) return "set";
            if (UNSET.equals(t.now())) return "unset";
            return "changed";
        }
        if (t.was() == null) return "set";
        if (t.now() == null) return "unset";
        return "changed";
    }

    private static final String UNSET = "unset";

    /** One token's name as the reason reads it. */
    static String describe(String name) {
        if (name.startsWith("src:")) return "test source " + tail(name.substring(4)) + " changed";
        if (name.startsWith("main")) return "main classes changed";
        if (name.startsWith("res:")) return "test resources changed";
        if (name.equals("cp" + ClasspathFingerprint.ORDER)) return "runtime classpath order changed";
        if (name.startsWith("cp:")) return "runtime classpath entry " + entry(name.substring(3)) + " changed";
        if (name.startsWith("x:read:")) return "read file " + path(name.substring(7)) + " changed";
        if (name.startsWith("x:list:")) return "the listing of read dir " + path(name.substring(7)) + " changed";
        if (name.startsWith("x:proc:")) return "program " + tail(name.substring(7)) + " changed";
        // The caller's environment: a variable by name, never its value (SuiteEnvReads).
        if (name.startsWith("x:env-read:")) return path(name.substring(11)) + " changed";
        if (name.startsWith("x:env-names")) return "the environment's variable names changed";
        if (name.startsWith("x:env-all")) return "the environment changed (a test starts programs)";
        if (name.equals(ActionRecordText.HOST_KEYED_INPUT))
            return "what the environment keys on this host alone changed";
        if (name.startsWith("x:worker:")) return workerChanged(upTo(name.substring(9), '='));
        if (name.startsWith("x:test-jdk")) return "test JDK changed";
        if (name.startsWith("x:compile-test")) return "test compile changed";
        String bare = name.startsWith("x:") ? name.substring(2) : name;
        int colon = bare.indexOf(':');
        return (colon > 0 ? bare.substring(0, colon) : bare) + " changed";
    }

    /** The reason a suite runs for the worker jar {@code jarProperty} names: {@code worker <jar> changed}. */
    public static String workerChanged(String jarProperty) {
        return "worker " + jarProperty + " changed";
    }

    private static String upTo(String s, char c) {
        int at = s.indexOf(c);
        return at > 0 ? s.substring(0, at) : s;
    }

    /** A path token by its last two segments, which name the file without the whole path. */
    /** The suites a token map's run ran ({@code x:suites-run:}), or null when it names none. */
    private static @Nullable String suitesRun(Map<String, String> tokens) {
        for (String name : tokens.keySet()) {
            if (name.startsWith(SUITES_RUN)) return upTo(name.substring(SUITES_RUN.length()), '?');
        }
        return null;
    }

    private static final String SUITES_RUN = "x:suites-run:";

    /** A token's whole path, workspace-relative as {@code SuiteReads.tokens} spells it, less its value. */
    private static String path(String token) {
        int eq = token.indexOf('=');
        return eq > 0 ? token.substring(0, eq) : token;
    }

    /** A jar by its file name; a class tree by its whole name, since its last segments are every module's. */
    private static String entry(String name) {
        return name.endsWith(".jar") ? name.substring(name.lastIndexOf('/') + 1) : name;
    }

    private static String tail(String path) {
        int eq = path.indexOf('=');
        String p = eq > 0 ? path.substring(0, eq) : path;
        int last = p.lastIndexOf('/');
        int before = last > 0 ? p.lastIndexOf('/', last - 1) : -1;
        return before >= 0 ? p.substring(before + 1) : p;
    }
}
