// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.EnvReadKey;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.task.KeySeed;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What a test suite read of the caller's environment, and how that keys its result.
 *
 * <p>A test JVM fetches a variable of the shell running {@code jk} when it first looks it up
 * ({@link EnvBroker}); the engine notes each name it hands over. The names (never the values) are
 * kept with what the suite was found to read ({@link SuiteReads.Discovered#env}), and from then on
 * the suite's key carries, for each, a digest of its value in the caller's shell now — or that it
 * is unset, which keys as well: setting it later runs the suite again. Two coarser facts key what
 * no single name says: the suite listed the environment's names ({@link Record#listed}: keyed on
 * the names present, never on values it did not read), or a test started a program, which reads
 * the whole environment unseen ({@link Record#whole}: keyed on every value). Both leave out the
 * names that differ between two terminals of one user ({@link #VOLATILE}) and the machine names
 * jk hands every worker anyway ({@link WorkerEnv#allowed}).
 *
 * <p>A value enters a key as an HMAC under a secret of this jk home ({@link EnvReadKey}), never as
 * a plain digest: a key may travel, and a plain digest of a short value is guessed by trying
 * values, by anyone who holds the key and its other inputs. So a result that read a value, listed
 * the names or took the whole environment keys alike on this host alone, under {@code [env] scope}
 * too, and carries {@link #HOST_TOKEN}, which never leaves it ({@code
 * ActionRecordText.shareable}); a result whose reads were all unset keys no value and is shared as
 * it is. What the test JVM could not have been handed under the module's policy now (a name outside
 * the scope, or one it declares since) keys nothing, whatever an earlier run under another policy
 * kept ({@link Record#under}).
 *
 * <p>With a remote cache whose key seed is in force (the one it issues, or the team's own: {@code
 * CacheSeeds}), such a key has a second, shared form ({@link #sharedTokens}): each value HMAC'd
 * under the seed, so every host holding it keys alike, and the result is asked and uploaded by that
 * form ({@code SharedStampKeys}, {@code RemoteTier.shareAs}). A credential's value keys under the
 * seed too, unless the user keeps those on the host ({@code [cache] credential-env = "local"}); a
 * run with no caller has none. The local form never depends on the seed. jk's own seed variable
 * and credentials ({@link BuildEnv#withheld}) are never handed to a test JVM from the caller's
 * shell and key nothing: a read of one reads unset and is not recorded. A credential the module
 * forwards by name is held from launch, as any declared name is.
 */
public final class SuiteEnvReads {

    private SuiteEnvReads() {}

    /**
     * What one suite was found to read of the caller's environment: the variables it looked up by
     * name (unset ones too), whether it listed the names, whether it took the whole environment
     * (a test started a program). Names only; Windows names upper-cased, as Windows compares them.
     */
    public record Record(Set<String> names, boolean listed, boolean whole) {
        public static final Record NONE = new Record(Set.of(), false, false);

        public Record {
            Set<String> sorted = new TreeSet<>();
            for (String n : names) sorted.add(key(n));
            names = Set.copyOf(sorted);
        }

        /** This record with {@code more} added: a partial run adds to what the whole suite was found to read. */
        public Record with(Record more) {
            Set<String> all = new TreeSet<>(names);
            all.addAll(more.names());
            return new Record(all, listed || more.listed(), whole || more.whole());
        }

        public boolean isEmpty() {
            return names.isEmpty() && !listed && !whole;
        }

        /**
         * This record as the test JVM could read it under {@code policy}: the names its broker would
         * hand over now ({@link Policy#handed}). What a suite read only grows, so a run before a
         * scope, or before the module declared a name, leaves names the JVM can no longer be handed:
         * it sees them unset on every host, and they key nothing.
         */
        public Record under(Policy policy) {
            Set<String> kept = new TreeSet<>();
            for (String n : names) {
                if (policy.handed(n)) kept.add(n);
            }
            return kept.size() == names.size() ? this : new Record(kept, listed, whole);
        }

        /** The lines {@link SuiteReads} keeps this record as. */
        List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String n : new TreeSet<>(names)) out.add(NAME_LINE + n);
            if (listed) out.add(LISTED_LINE);
            if (whole) out.add(WHOLE_LINE);
            return out;
        }

        /** {@code line} read into {@code into} when it is one of {@link #lines}'s; false otherwise. */
        static boolean read(String line, Set<String> names, boolean[] flags) {
            if (line.startsWith(NAME_LINE)) names.add(line.substring(NAME_LINE.length()));
            else if (line.equals(LISTED_LINE)) flags[0] = true;
            else if (line.equals(WHOLE_LINE)) flags[1] = true;
            else return false;
            return true;
        }
    }

    private static final String NAME_LINE = "env ";
    private static final String LISTED_LINE = "env-names";
    private static final String WHOLE_LINE = "env-all";

    /**
     * What a module's test JVMs may take of the caller's environment: nothing past the allow-list
     * and the declared names when {@code strict}; under a {@code scope}, only the names it matches.
     */
    public record Policy(boolean strict, List<String> scope, Set<String> declared) {
        public Policy {
            scope = List.copyOf(scope);
            Set<String> d = new TreeSet<>();
            for (String n : declared) d.add(key(n));
            declared = Set.copyOf(d);
        }

        /** No declaration, no scope, not strict: a test JVM may read any variable of the caller's. */
        public static final Policy NONE = new Policy(false, List.of(), Set.of());

        /** The policy {@code project}'s manifest sets. */
        public static Policy of(JkBuild project) {
            EnvConfig env = project.build().env();
            Set<String> declared = new LinkedHashSet<>(env.declared());
            for (var d : env.vars()) declared.add(d.name());
            for (var d : project.build().testEnvDecls()) declared.add(d.name());
            project.profiles()
                    .byName()
                    .values()
                    .forEach(p -> declared.addAll(p.env().keySet()));
            project.build()
                    .testSuites()
                    .values()
                    .forEach(s -> declared.addAll(s.settings().env().keySet()));
            return new Policy(env.strict(), env.scope(), declared);
        }

        /**
         * Whether the test JVM already holds {@code name} when it is set: jk's allow-list, a name
         * the module declares, or one set on every JVM. Such a read is no read of the caller's
         * shell: it is keyed as the declaration says, or deliberately not at all.
         */
        public boolean held(String name) {
            if (BuildEnv.refused(name)) return false;
            return declared.contains(key(name))
                    || WorkerEnv.allowed(name, Os.isWindows())
                    || WorkerEnv.alwaysComposed(name);
        }

        /** Whether a test JVM may see {@code name} of the caller's: every name, or under a scope only those it matches. */
        public boolean visible(String name) {
            String k = key(name);
            if (BuildEnv.withheld(k)) return false;
            if (scope.isEmpty()) return true;
            for (String pattern : scope) {
                String p = key(pattern);
                if (p.endsWith("*") ? k.startsWith(p.substring(0, p.length() - 1)) : k.equals(p)) return true;
            }
            return false;
        }

        /**
         * Whether the broker hands a test JVM {@code name} of the caller's: one the JVM does not
         * already hold ({@link #held}) and the module lets it see ({@link #visible}).
         */
        public boolean handed(String name) {
            return !held(name) && visible(name);
        }
    }

    /**
     * The names that come and go, or change value, between two sessions of one user on one machine:
     * a new terminal tab, an ssh login, a multiplexer pane, another terminal program. Left out of the
     * name set a listing suite keys on and of the whole environment a suite that starts programs keys
     * on, and never recorded when a test reads one by name ({@link EnvBroker}): Micronaut and Flyway list
     * the environment and read every value, which would otherwise key their suites on the terminal they
     * ran in. The cost, stated in docs/user/test.md: a test whose outcome depends on one of these
     * values (the terminal's {@code TERM}, the shell's {@code PWD}) can replay from another terminal.
     * Names only; a value never decides.
     *
     * <p>Measured (two sessions of one user, compared by name and value digest on the host): a login
     * over ssh on Windows, macOS and WSL changes {@code SSH_CLIENT} and {@code SSH_CONNECTION}; a
     * login shell against a plain one adds {@code MOTD_SHOWN} and moves {@code OLDPWD} and {@code
     * SHLVL}; the starship prompt sets a per-session {@code STARSHIP_SESSION_KEY}; JumpKick's own
     * shell hook keeps {@code __JK_*} per directory. The rest is what terminals, multiplexers and
     * desktop sessions document setting per window or login: the shell's per-command state, the
     * terminal's identity and size, the session bus and seat.
     */
    public static final Set<String> VOLATILE = Set.of(
            // the shell's per-command state
            "_",
            "PWD",
            "OLDPWD",
            "SHLVL",
            "PS1",
            "PROMPT",
            "MOTD_SHOWN",
            "MAIL",
            "STARSHIP_SESSION_KEY",
            // the terminal, its window and its colour switches
            "TERM",
            "COLORTERM",
            "TERM_PROGRAM",
            "TERM_PROGRAM_VERSION",
            "TERM_SESSION_ID",
            "TERMINAL_EMULATOR",
            "COLUMNS",
            "LINES",
            "WINDOWID",
            "VTE_VERSION",
            "WT_SESSION",
            "WT_PROFILE_ID",
            "LC_TERMINAL",
            "LC_TERMINAL_VERSION",
            "INSIDE_EMACS",
            "NO_COLOR",
            "FORCE_COLOR",
            "CLICOLOR",
            "CLICOLOR_FORCE",
            // multiplexers
            "TMUX",
            "TMUX_PANE",
            "STY",
            "WINDOW",
            // the login and the desktop session
            "GPG_TTY",
            "DESKTOP_SESSION",
            "GDMSESSION",
            "SESSION_MANAGER",
            "DBUS_SESSION_BUS_ADDRESS",
            "INVOCATION_ID",
            "JOURNAL_STREAM",
            "SECURITYSESSIONID",
            "WSL_INTEROP",
            "SESSIONNAME",
            "CLIENTNAME",
            // the shell itself: PowerShell rewrites its module path for the processes it starts, so a
            // jk run from PowerShell and one from cmd differ there (measured on Windows 11, PowerShell
            // 5.1); a launch with -ExecutionPolicy sets the preference for that session alone
            "PSMODULEPATH",
            "PSEXECUTIONPOLICYPREFERENCE");

    /**
     * The families of {@link #VOLATILE} names, by prefix: the ssh login ({@code SSH_CLIENT}, {@code
     * SSH_CONNECTION}, {@code SSH_TTY}, {@code SSH_AUTH_SOCK}), each terminal program's own window
     * and pane ids, an editor's terminal, macOS's per-login sockets, JumpKick's shell hook, and
     * Windows' per-drive directories ({@code =C:}).
     */
    static final List<String> VOLATILE_PREFIXES = List.of(
            "SSH_",
            "GNOME_TERMINAL_",
            "KONSOLE_",
            "KITTY_",
            "ALACRITTY_",
            "WEZTERM_",
            "ITERM_",
            "ZELLIJ",
            "VSCODE_",
            "Apple_PubSub_",
            "__CF",
            "XPC_",
            "__JK_",
            "=");

    /** Whether {@code name} comes and goes between two sessions of one user ({@link #VOLATILE}). */
    public static boolean volatileName(String name) {
        if (name.isEmpty() || VOLATILE.contains(name) || VOLATILE.contains(name.toUpperCase(Locale.ROOT))) return true;
        for (String prefix : VOLATILE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The key token prefixes this class makes. */
    static final String READ_TOKEN = "env-read:";

    static final String NAMES_TOKEN = "env-names=";
    static final String ALL_TOKEN = "env-all=";

    /**
     * The token of a key that holds a value only this jk home can key alike ({@link #localOnly}):
     * the stamp's input {@link ActionRecordText#HOST_KEYED_INPUT}, which keeps its record on this host.
     */
    public static final String HOST_TOKEN =
            ActionRecordText.HOST_KEYED_INPUT.substring("x:".length()) + "=" + ActionRecordText.HOST_KEYED;

    /** Whether {@code token} is one {@link #tokens} or {@link #sharedTokens} makes. */
    public static boolean isToken(String token) {
        return token.startsWith(READ_TOKEN)
                || token.startsWith(NAMES_TOKEN)
                || token.startsWith(ALL_TOKEN)
                || token.equals(HOST_TOKEN);
    }

    /**
     * The key tokens of {@code record} for a run whose caller is {@code caller}: one per name read
     * that the module's policy hands over ({@code env-read:NAME=<digest>}, {@code =unset}, or {@code
     * =unknown} when the run has no caller to say), one for a listing ({@code env-names=}) and a
     * whole environment ({@code env-all=}), and {@link #HOST_TOKEN} when the result keys alike on
     * this host alone ({@link #localOnly}). A digest is an HMAC under this jk home's secret, scoped
     * or not, credential or not: this is the key's local form, and it never depends on a seed.
     */
    public static List<String> tokens(Record record, Policy policy, CallerEnv caller) {
        return keyed(record, policy, caller, null).tokens();
    }

    /**
     * The tokens of {@code record}'s shared form under the remote cache's {@code seed}: each value a
     * suite read keyed by HMAC under the seed instead of this home's secret, so another host holding
     * the same seed and the same value keys alike, and no {@link #HOST_TOKEN}. A value that reads as
     * a credential ({@link BuildEnv#looksSecret}) is keyed under the seed like any other, unless
     * {@code credentialsLocal} (the user's {@code [cache] credential-env = "local"}): then a result
     * that read one has no shared form. Null too for a run with no caller. The local form ({@link
     * #tokens}) never depends on the seed, so a seed that arrives, changes or goes reruns nothing here;
     * a record whose reads were all unset keys alike in both forms.
     */
    public static @Nullable List<String> sharedTokens(
            Record record, Policy policy, CallerEnv caller, KeySeed seed, boolean credentialsLocal) {
        Keyed keyed = keyed(record, policy, caller, seed);
        if (!caller.attached() || (credentialsLocal && keyed.credential() != null)) return null;
        return keyed.tokens();
    }

    /**
     * Why a result keyed on {@code record} for {@code caller} stays on this host, or null when it may
     * be shared as it is: it read the value of a variable, listed the environment's names, started a
     * program, which takes the whole environment, or ran with no caller to answer for it. Each of
     * those keys under this home's secret, scoped or not, so its key matches nowhere else; under a
     * remote cache's seed it has a shared form besides ({@link #sharedTokens}). A record whose reads
     * were all unset keys no value, and is shared as it is.
     */
    public static @Nullable String localOnly(Record record, Policy policy, CallerEnv caller) {
        return keyed(record, policy, caller, null).localOnly();
    }

    /**
     * The tokens of a record, why they key on this host alone (null when they do not), and the first
     * name read with a value that reads as a credential (null when none was).
     */
    private record Keyed(
            List<String> tokens,
            @Nullable String localOnly,
            @Nullable String credential) {}

    private static Keyed keyed(Record found, Policy policy, CallerEnv caller, @Nullable KeySeed seed) {
        if (found.isEmpty() || policy.strict()) return new Keyed(List.of(), null, null);
        Record record = found.under(policy);
        if (record.isEmpty()) return new Keyed(List.of(), null, null);
        Keying keying = new Keying(seed);
        List<String> out = new ArrayList<>();
        String local = caller.attached() ? null : "ran with no caller to answer for the environment";
        String credential = null;
        Map<String, CallerEnv.Value> values = caller.values(record.names());
        for (String name : new TreeSet<>(record.names())) {
            CallerEnv.Value v = values.getOrDefault(name, CallerEnv.Value.UNKNOWN);
            out.add(READ_TOKEN + name + "=" + digest(keying, name, v));
            if (v.state() != CallerEnv.State.SET) continue;
            if (local == null) local = "read the value of " + name;
            if (credential == null && BuildEnv.looksSecret(name)) credential = name;
        }
        if (record.listed()) {
            Set<String> names = caller.names();
            List<String> keyed = names == null ? List.of() : keyedNames(names, policy);
            out.add(NAMES_TOKEN + (names == null ? UNKNOWN : keying.aggregate(String.join("\n", keyed))));
            if (KeyAudit.listening()) keyed.forEach(n -> KeyAudit.tap(NAME_DETAIL + n));
            if (local == null) local = "listed the environment's names";
        }
        if (record.whole()) {
            Map<String, String> all = caller.all();
            Map<String, String> visible = all == null ? Map.of() : visibleValues(all, policy);
            out.add(ALL_TOKEN + (all == null ? UNKNOWN : keying.aggregate(keyedValues(visible))));
            if (KeyAudit.listening())
                visible.forEach((n, v) -> KeyAudit.tap(VALUE_DETAIL + n + "=" + keying.value(n, v)));
            if (local == null) local = "started a program, which sees the whole environment";
            for (String n : visible.keySet()) {
                if (credential == null && BuildEnv.looksSecret(n)) credential = n;
            }
        }
        if (local != null && seed == null) out.add(HOST_TOKEN);
        return new Keyed(out, local, credential);
    }

    /**
     * How a value enters a key: by HMAC under this jk home's secret ({@link EnvReadKey}, read only
     * when a value needs it), or in a key's shared form ({@link #sharedTokens}) under the remote
     * cache's seed ({@link KeySeed}). Never by plain digest: a digest of a short value is guessed by
     * trying values, by anyone who holds the key and the rest of its inputs.
     */
    private static final class Keying {
        private final @Nullable KeySeed seed;
        private @Nullable EnvReadKey home;

        Keying(@Nullable KeySeed seed) {
            this.seed = seed;
        }

        /** {@code name}'s {@code value} as a key holds it. */
        String value(String name, String value) {
            return hmac(name + "\u0000" + value);
        }

        /** A listing's names, or a whole environment as {@link #keyedValues} spells it. */
        String aggregate(String text) {
            return hmac("\u0000" + text);
        }

        String hmac(String text) {
            KeySeed s = seed;
            if (s != null) return s.hmac(text);
            EnvReadKey k = home;
            if (k == null) home = k = EnvReadKey.load();
            return k.hmac(text);
        }
    }

    /**
     * What a declared variable's value that the caller's environment answered ({@code [env]} and
     * {@code [test] env} forwards, a {@code ${VAR}} in a value) becomes in a suite's {@code test-env:}
     * token: an HMAC under this jk home's secret, or in the key's shared form under the remote cache's
     * {@code seed}; never a plain digest. The key then carries {@link #HOST_TOKEN} in its local form,
     * as for a value read undeclared ({@code SharedStampKeys.declaredTokens}).
     */
    public static String declaredValue(String value, @Nullable KeySeed seed) {
        return "hmac:" + new Keying(seed).hmac("test-env\u0000" + value);
    }

    /**
     * The names behind a listing's token ({@code env-name:NAME}) and the digests behind a whole
     * environment's ({@code env-value:NAME=<digest>}), named to the key's audit and never hashed:
     * the digest of every name or value says only that one moved, and these let a miss name which
     * ({@code StampTokens}). They reach this host's own records and kept tokens; a shared record
     * carries none ({@code ActionRecordText.renderShared}).
     */
    public static final String NAME_DETAIL = "env-name:";

    public static final String VALUE_DETAIL = "env-value:";

    private static final String UNKNOWN = "unknown";

    private static String digest(Keying keying, String name, CallerEnv.Value v) {
        String value = v.value();
        return switch (v.state()) {
            case UNSET -> "unset";
            case UNKNOWN -> UNKNOWN;
            case SET -> keying.value(name, value == null ? "" : value);
        };
    }

    /** The names a listing keys on: those the module may see, less the volatile and the allow-listed, sorted. */
    static List<String> keyedNames(Set<String> names, Policy policy) {
        Set<String> out = new TreeSet<>();
        for (String n : names) {
            if (policy.visible(n) && !volatileName(n) && !WorkerEnv.allowed(n, Os.isWindows())) out.add(key(n));
        }
        return new ArrayList<>(out);
    }

    /** The whole environment as a suite that starts programs keys on, from {@link #visibleValues}, one line a name. */
    private static String keyedValues(Map<String, String> visible) {
        StringBuilder sb = new StringBuilder();
        visible.forEach((n, v) -> sb.append(n).append('=').append(v).append('\n'));
        return sb.toString();
    }

    /** What a whole environment keys on: what the test JVM may see, less the volatile, by name, sorted. */
    private static Map<String, String> visibleValues(Map<String, String> all, Policy policy) {
        Map<String, String> sorted = new TreeMap<>();
        all.forEach((n, v) -> {
            if (policy.visible(n) && !volatileName(n)) sorted.put(key(n), v);
        });
        return sorted;
    }

    /**
     * Every variable name the suites of {@code moduleDirs} were found to read, with {@link
     * ProjectInfo#ENV_READS_NAMES} when one listed the names and {@link ProjectInfo#ENV_READS_ALL}
     * when one started a program: what the client ships with its next request, so their keys are
     * taken with no question back. Names only.
     */
    public static List<String> wanted(Collection<String> moduleDirs) {
        Set<String> out = new TreeSet<>();
        for (String dir : moduleDirs) {
            Path kept = SuiteReads.discoveredFile(Path.of(dir), TestSelection.DEFAULT)
                    .getParent();
            if (kept == null || !Files.isDirectory(kept)) continue;
            try {
                PathUtil.forEachRegularFile(kept, (file, attrs) -> {
                    if (!file.getFileName().toString().startsWith("test-inputs-")) return;
                    Set<String> names = new TreeSet<>();
                    boolean[] flags = new boolean[2];
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                        Record.read(line, names, flags);
                    out.addAll(names);
                    if (flags[0]) out.add(ProjectInfo.ENV_READS_NAMES);
                    if (flags[1]) out.add(ProjectInfo.ENV_READS_ALL);
                });
            } catch (IOException | RuntimeException none) {
                // a module whose suites never ran reads nothing yet
            }
        }
        return List.copyOf(out);
    }

    /** {@code name} as the record keeps it: upper-cased on Windows, where case does not tell names apart. */
    static String key(String name) {
        return Os.isWindows() ? name.toUpperCase(Locale.ROOT) : name;
    }
}
