// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where a probed class's {@code System.getenv} calls land, and how the test JVM sees the caller's
 * environment.
 *
 * <p>A test JVM starts from jk's allow-list and the module's declared names, as every worker does.
 * When the engine set up a broker for the run ({@link BrokerLink}), a variable outside those is
 * fetched from the caller's shell the first time something looks it up, through the engine, and
 * kept in memory for the JVM's life: the engine records each name it hands over, so what the suite
 * read is known exactly and keys its result. The no-argument {@code System.getenv()} answers a view
 * of the same environment ({@link RecordingEnv}) whose values are fetched only when read, and a
 * program the JVM starts gets the caller's whole environment beneath what the test set for it
 * ({@link #start}).
 *
 * <p>Each lookup is also noted — the variable's name, whether it had a value, the test class
 * running and the class that asked — for the report a strict module gets. Values are never written.
 *
 * <p>The two {@code getenv} methods carry {@code System.getenv}'s exact names and descriptors:
 * {@link EnvProbe} repoints a class's reference to {@code java/lang/System} at this class and leaves
 * the rest of the call as compiled. {@link #start} carries {@code ProcessImpl.start}'s, which
 * {@link EnvProbe} repoints {@code java.lang.ProcessBuilder} at.
 */
public final class EnvLookups {

    /** The system property the test runner sets to the class whose tests are running. */
    public static final String CURRENT_TEST = "jk.test.class";

    /** How a lookup that found a value is written. */
    public static final String SET = "set";

    /** How a lookup that found nothing is written. */
    public static final String UNSET = "unset";

    /** The name a whole-map read is noted under. */
    public static final String WHOLE_MAP = "*";

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** The engine's broker, when it set one up for this run. */
    private static volatile @Nullable BrokerLink broker;

    /** What the broker answered, by name: a value, or empty for a variable the caller's shell lacks. */
    private static final Map<String, Optional<String>> FETCHED = new ConcurrentHashMap<>();

    /** The names the caller's shell sets, once something listed them. */
    private static volatile @Nullable Set<String> listed;

    /** The caller's whole environment, once a program was started. */
    private static volatile @Nullable Map<String, String> whole;

    private static final RecordingEnv VIEW = new RecordingEnv();

    private EnvLookups() {}

    /** Fetch what the real environment lacks through {@code link} from now on. */
    static void deliverThrough(@Nullable BrokerLink link) {
        broker = link;
        FETCHED.clear();
        listed = null;
        whole = null;
    }

    /** Whether a broker answers for the caller's shell in this JVM. */
    static boolean delivering() {
        return broker != null;
    }

    /** {@code System.getenv(name)}, noted; from the caller's shell when this JVM's own lacks it. */
    public static @Nullable String getenv(String name) {
        String value = lookup(name);
        note(name, value != null);
        return value;
    }

    /** {@code System.getenv()}, noted as a read of every variable: a view whose values are fetched as read. */
    public static Map<String, String> getenv() {
        note(WHOLE_MAP, true);
        return delivering() ? VIEW : System.getenv();
    }

    /** {@code name}'s value: this JVM's own, else the caller's, fetched once. */
    static @Nullable String lookup(String name) {
        String own = System.getenv(name);
        BrokerLink link = broker;
        if (own != null || link == null) return own;
        String key = key(name);
        Optional<String> known = FETCHED.get(key);
        if (known != null) return known.orElse(null);
        Map<String, String> all = whole;
        if (all != null) return find(all, name);
        Set<String> names = listed;
        // Once the names are listed (and so key the suite), a name outside them is unset: no question.
        if (names != null && !contains(names, name)) {
            FETCHED.put(key, Optional.empty());
            return null;
        }
        Map<String, String> answer = link.values(List.of(name));
        String v = answer == null ? null : find(answer, name);
        FETCHED.put(key, Optional.ofNullable(v));
        return v;
    }

    /** The names this JVM can see: its own and the caller's, listed once. */
    static Set<String> names() {
        Set<String> out = new LinkedHashSet<>(System.getenv().keySet());
        BrokerLink link = broker;
        if (link == null) return out;
        Set<String> names = listed;
        if (names == null) {
            Map<String, String> all = whole;
            names = all != null ? all.keySet() : link.names();
            if (names == null) names = Set.of();
            listed = names;
        }
        out.addAll(names);
        return out;
    }

    /** The caller's whole environment, fetched once, for a program this JVM starts. */
    static Map<String, String> whole() {
        Map<String, String> all = whole;
        if (all != null) return all;
        BrokerLink link = broker;
        all = link == null ? null : link.all();
        if (all == null) all = Map.of();
        whole = all;
        return all;
    }

    private static boolean contains(Set<String> names, String name) {
        if (names.contains(name)) return true;
        if (!WINDOWS) return false;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static @Nullable String find(Map<String, String> vars, String name) {
        String v = vars.get(name);
        if (v != null || !WINDOWS) return v;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String key(String name) {
        return WINDOWS ? name.toUpperCase(Locale.ROOT) : name;
    }

    /**
     * Whether variable names compare without case, as Windows compares them. The probe runs on the
     * boot class path with the JDK alone, so it asks the file system's separator rather than jk's
     * host predicate.
     */
    private static final boolean WINDOWS = File.separatorChar == '\\';

    // ---- programs the JVM starts ----------------------------------------------------------------

    private static volatile @Nullable MethodHandle processStart;
    private static volatile @Nullable MethodHandle freshEnvironment;

    /** Ready {@link #start} to hand on to the JDK's own launch; false when this JDK does not allow it. */
    static boolean prepareStart() {
        try {
            Class<?> impl = Class.forName("java.lang.ProcessImpl");
            MethodHandles.Lookup in = MethodHandles.privateLookupIn(impl, MethodHandles.lookup());
            processStart = in.findStatic(
                    impl,
                    "start",
                    MethodType.methodType(
                            Process.class,
                            String[].class,
                            Map.class,
                            String.class,
                            ProcessBuilder.Redirect[].class,
                            boolean.class));
            Class<?> env = Class.forName("java.lang.ProcessEnvironment");
            freshEnvironment = MethodHandles.privateLookupIn(env, MethodHandles.lookup())
                    .findStatic(env, "environment", MethodType.methodType(Map.class));
            return true;
        } catch (ReflectiveOperationException | RuntimeException notHere) {
            return false;
        }
    }

    /**
     * {@code ProcessImpl.start}, which {@code ProcessBuilder.start} calls: the program gets the
     * caller's whole environment beneath this JVM's own and beneath what the test set for it. A
     * test that took a variable of this JVM's out of the program's environment (or cleared it) said
     * what the program should see, and gets exactly that.
     */
    public static Process start(
            String[] cmdarray,
            @Nullable Map<String, String> environment,
            @Nullable String dir,
            ProcessBuilder.Redirect[] redirects,
            boolean redirectErrorStream)
            throws IOException {
        MethodHandle launch = processStart;
        if (launch == null) throw new IOException("jk's environment probe could not reach the JDK's process launch");
        Map<String, String> env = environment;
        if (delivering()) env = childEnvironment(environment);
        try {
            return (Process) launch.invoke(cmdarray, env, dir, redirects, redirectErrorStream);
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** {@code environment} (null: this JVM's own) with the caller's variables it lacks added beneath. */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> childEnvironment(@Nullable Map<String, String> environment) {
        MethodHandle fresh = freshEnvironment;
        if (fresh == null) return environment;
        Map<String, String> own = System.getenv();
        if (environment != null) {
            for (String name : own.keySet()) {
                if (!environment.containsKey(name)) return environment; // the test chose what it sees
            }
        }
        try {
            Map<String, String> out = (Map<String, String>) fresh.invoke();
            if (environment != null) {
                out.clear();
                out.putAll(environment);
            }
            for (Map.Entry<String, String> e : whole().entrySet()) {
                if (find(own, e.getKey()) == null && !out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
            }
            return out;
        } catch (Throwable t) {
            return environment;
        }
    }

    // ---- the strict module's report -------------------------------------------------------------

    private static void note(String name, boolean set) {
        String test = System.getProperty(CURRENT_TEST, "");
        String caller = WALKER.walk(frames -> frames.skip(2)
                .findFirst()
                .map(f -> f.getDeclaringClass().getName())
                .orElse(""));
        SEEN.add(test + '\t' + caller + '\t' + name + '\t' + (set ? SET : UNSET));
    }

    /** Writes every lookup noted so far to {@code file}, one per line: an empty file says the probe ran and saw none. */
    static void writeTo(Path file) {
        try {
            Files.createDirectories(file.getParent());
            String lines = SEEN.isEmpty() ? "" : String.join("\n", SEEN) + "\n";
            Files.write(file, lines.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // a diagnostic the JVM could not write is a diagnostic not given, never a failed suite
        }
    }
}
