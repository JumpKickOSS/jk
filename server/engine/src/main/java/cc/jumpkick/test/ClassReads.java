// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.orchestrator.DiscoveryRules;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What each test class of a suite did, read from the suite's recordings ({@link SuiteReads}): the
 * test runner marks each top-level class's window with a {@code jk.TestClass} event, and every
 * other event is joined to the classes whose window, in the same JVM, holds its start time.
 *
 * <ul>
 *   <li>Any thread: a background thread's read while a class runs is that class's. Overlapping
 *       windows (in-process parallelism) each get it.
 *   <li>Outside every window (discovery, a static initialiser before the first class, a shutdown
 *       hook): pooled, which stands for every class the suite ran.
 *   <li>After a class finished: never that class's.
 *   <li>A socket read on a thread whose first read or write of another host a class's window held:
 *       that class's, wherever it falls. A server closes an idle connection minutes later.
 *   <li>A code file (a jar, a class file outside the class trees) the JVM read: the class in whose
 *       window it was read and every class that ran later in that JVM, since a class loader keeps
 *       what it loaded for whoever asks next.
 * </ul>
 *
 * <p>A class file read under one of the suite's class trees is a class loading: noted by name, as
 * the observed half of the class's cone. A class loads once per JVM, so a class's loads are not all
 * the classes it ran: where the probe marks the workspace's classes (its class trees and siblings' jars), a {@code jk.ClassUse} event
 * names each one whose code ran in the window ({@link Observation#used}), and a use outside every
 * window counts for every class its JVM ran. Reads of the operating system's own trees and native
 * libraries outside the JDK are platform evidence: kept, never keyed.
 */
public final class ClassReads {

    private ClassReads() {}

    /** The event the test runner marks a class's window with. */
    public static final String WINDOW_EVENT = "jk.TestClass";

    /** The event the probe names a marked class with, the first time its code runs in a window. */
    public static final String USE_EVENT = "jk.ClassUse";

    /**
     * The most distinct classes a suite's uses name, every JVM's together; past it a class that uses
     * one more says so ({@link Observation#usedMore}) and its cone keys every class by its bytes.
     * jk-engine's class trees hold some thousands.
     */
    public static final int USED_NAMES_KEPT = 16_384;

    /**
     * What a read means, by where it lies.
     *
     * @param classTrees the directories classes load from by file: test classes, main classes,
     *     fixtures, siblings' class trees
     * @param keyed roots the suite's key already covers whole (the JDK, the external classpath, the
     *     resource roots, the module's build output, the run's sandbox): a read there is none of the
     *     class's own
     * @param externalJars the jars on the suite's runtime classpath: a read of one loads a library
     *     the suite's key holds, or a workspace module's classes, which the cone holds class by class
     * @param workspace the workspace root, which paths in a record are spelled relative to
     * @param javaHome the test JDK, whose own native libraries are no evidence of anything
     * @param scratch the run's own trees (the module's build output, the test sandbox): whatever is
     *     read there, code included, the run made, so it is no input of any class
     * @param temp the test JVMs' own temp roots, outside the workspace: a repository there is one a
     *     test made, so git run in it reads no input of the class ({@link GitRuns})
     */
    public record Layout(
            List<Path> classTrees,
            List<Path> keyed,
            Set<Path> externalJars,
            Path workspace,
            @Nullable Path javaHome,
            List<Path> scratch,
            List<Path> temp) {
        public Layout {
            classTrees = List.copyOf(normalized(classTrees));
            // Each root under both spellings (a store reached through a link, macOS's /private/var).
            keyed = List.copyOf(withReal(normalized(keyed)));
            externalJars = Set.copyOf(withReal(normalized(externalJars)));
            workspace = workspace.toAbsolutePath().normalize();
            javaHome = javaHome == null ? null : javaHome.toAbsolutePath().normalize();
            scratch = List.copyOf(normalized(scratch));
            temp = List.copyOf(normalized(temp));
        }

        /** A layout with no temp roots: every program a class started keys it by its module. */
        public Layout(
                List<Path> classTrees,
                List<Path> keyed,
                Set<Path> externalJars,
                Path workspace,
                @Nullable Path javaHome,
                List<Path> scratch) {
            this(classTrees, keyed, externalJars, workspace, javaHome, scratch, List.of());
        }

        /** A layout with no scratch trees of its own. */
        public Layout(
                List<Path> classTrees,
                List<Path> keyed,
                Set<Path> externalJars,
                Path workspace,
                @Nullable Path javaHome) {
            this(classTrees, keyed, externalJars, workspace, javaHome, List.of(), List.of());
        }
    }

    /**
     * The most data files a class keeps; past it the class keys on the files its suite read under
     * its read roots ({@link #ROOTS_KEPT}).
     */
    public static final int READS_KEPT = 8;

    /**
     * The most directories a class's read roots hold: together they cover every data file it read,
     * each a directory whose files it read or, once two are joined, everything under their deepest
     * common directory ({@link ReadRoot}).
     */
    public static final int ROOTS_KEPT = 8;

    /**
     * The most file names a class's data reads keep: past its read bound it keys only on the files
     * of its suite's reads under its roots that carry one of them, so a class that read every
     * module's {@code jk.toml} does not stand on the sources a neighbour scanned.
     */
    public static final int READ_NAMES_KEPT = 8;

    /**
     * A directory that covers a class's data reads: the files directly in it, or with {@code deep}
     * everything under it.
     */
    public record ReadRoot(Path dir, boolean deep) {
        /** Whether this root covers the file {@code p}. */
        public boolean covers(Path p) {
            return deep ? p.startsWith(dir) : dir.equals(p.getParent());
        }
    }

    /**
     * The most data files the pool keeps, what no class's window held: one list for the whole suite,
     * so it can name what a suite reads at discovery (templates, a registry) where a class's bound
     * would key every class on every file the suite read. Held within a record row's bound.
     */
    public static final int POOLED_READS_KEPT = 400;

    /**
     * The most code files a class keeps; past it the class stands on every code file its suite read
     * ({@link #SUITE_CODE_KEPT}).
     */
    public static final int CODE_KEPT = 4;

    /**
     * The most code files a suite's record names, every JVM's together: what a class that read more
     * than its own {@link #CODE_KEPT} stands on. A JVM's code files are charged to every class that
     * ran after it read them, so one in-process compile that loads a few dozen jars puts every later
     * class of its JVM past its own bound; the suite's list names them once. Past this, such a class
     * is keyed by its whole module.
     */
    public static final int SUITE_CODE_KEPT = 128;

    /** The most programs keyed by their identity alone a class keeps; past it, a program started. */
    public static final int TOOLS_KEPT = 8;

    /**
     * The name a class's tools keep for a program the test wrote in its temp root: its bytes are the
     * test's output, which the class's cone decides.
     */
    public static final String WRITTEN_PROGRAM = "(written)";

    /**
     * The name a class's tools keep when a program other than git in a repository a test made was
     * started in its JVM before its window closed, by it or by a class before it: what such a program
     * runs (a jar the suite was handed by path, a long-lived engine a later class talks to) is read
     * in a process no recording of the suite holds. The test JDK's own launcher counts.
     */
    public static final String STARTED_PROGRAM = "(started)";

    /**
     * The name a class's tools keep when it started a program that runs no class of its JVM's class
     * path ({@link ClassPathCommands}): what such a program reads stands on what it is, which the
     * suite's stamp says of every program the suite started, not on the workspace's classes.
     */
    public static final String EXTERNAL_PROGRAM = "(external)";

    /** The most of each kind of platform evidence a class keeps: presence decides, a few say what. */
    public static final int EVIDENCE_KEPT = 2;

    /**
     * The most workspace class loads a suite's classes keep between them; past it each class that
     * loads one more is keyed by its whole module. A class loads from a class tree once per JVM, so a
     * suite's loads are bounded by its module's classes times its JVMs: jk-engine's are some
     * thousands.
     */
    public static final int LOADED_KEPT = 65_536;

    /**
     * The most test classes of one suite that are attributed; a class past it keeps no observation
     * and is keyed by its whole module. jk's largest suite has about 1,000.
     */
    public static final int CLASSES_KEPT = 2_048;

    /**
     * The most heap one class's observation holds: its kept paths and evidence at a couple of hundred
     * bytes each, its tools' names at 64 and its empty sets.
     */
    public static final long CLASS_BYTES = (READS_KEPT + ROOTS_KEPT + CODE_KEPT + 4L * EVIDENCE_KEPT) * 200
            + (TOOLS_KEPT + READ_NAMES_KEPT) * 64L
            + 512;

    /** The most environment variables and system properties a class's record names, each. */
    public static final int NAMES_KEPT = 8;

    /**
     * The most heap recording one suite's classes holds outside the recording reader's permit: its
     * classes' observations, its pool and its loads, the names each class looked up, and one JVM's
     * join at a time. Memory admission keeps it free ({@code MemoryAdmission.RESERVE_BYTES}); one
     * suite records at a time.
     */
    public static final long PEAK_BYTES = (CLASSES_KEPT + 1) * CLASS_BYTES
            + CLASSES_KEPT * 2L * NAMES_KEPT * 64
            + LOADED_KEPT * 48L
            + USED_NAMES_KEPT * 64L
            + (CLASSES_KEPT + 1) * (USED_NAMES_KEPT / 8L)
            + POOLED_READS_KEPT * 200L
            + SUITE_CODE_KEPT * 200L
            + (ClassReads.WRITTEN_KEPT + ClassReads.CODE_FILES_KEPT + ClassReads.EXISTS_KEPT) * 200L
            + ClassReads.NETWORK_THREADS_KEPT * 200L
            + ClassReads.VERDICTS_KEPT * 600L;

    /**
     * The most files one JVM is noted writing among those that could be inputs; past it a read of
     * another file the JVM wrote counts as an input, which keys more and misses nothing.
     */
    static final int WRITTEN_KEPT = 8_192;

    /** The most distinct code files one JVM's join follows. */
    static final int CODE_FILES_KEPT = 2_048;

    /**
     * The most threads that reached another host one JVM's join follows; a read on a thread past it
     * goes by its time.
     */
    static final int NETWORK_THREADS_KEPT = 8_192;

    /** How many answers to "is this file there" one JVM's join remembers. */
    static final int EXISTS_KEPT = 2_048;

    /**
     * How many recorded paths a suite remembers the meaning of ({@link PathVerdicts}): its spelling,
     * its path and its class name, a couple of hundred bytes each.
     */
    static final int VERDICTS_KEPT = 2_048;

    /**
     * One class's share of a suite's run, or the pool's, bounded: each kind keeps at most so many
     * entries and says when it saw more ({@link #READS_KEPT}, {@link #CODE_KEPT}, {@link
     * #EVIDENCE_KEPT}, {@link #LOADED_KEPT}).
     */
    public static final class Observation {
        private final Set<Path> reads = new TreeSet<>();
        private final Set<Path> code = new TreeSet<>();
        private final Set<String> loaded = new HashSet<>();
        private final Set<String> processes = new TreeSet<>();
        private final Set<String> tools = new TreeSet<>();
        private final Set<String> nativeCode = new TreeSet<>();
        private final Set<Path> osPaths = new TreeSet<>();
        private final Set<String> hosts = new TreeSet<>();
        private boolean readsMore;
        private final List<ReadRoot> roots = new ArrayList<>();
        private boolean rootsMore;
        private final Set<String> names = new TreeSet<>();
        private boolean namesMore;
        private boolean codeMore;
        private boolean loadedMore;
        private final BitSet used = new BitSet();
        private @Nullable UseNames useNames;
        private boolean usedMore;
        private boolean bound;
        private final int readsKept;

        private Observation(int readsKept) {
            this.readsKept = readsKept;
        }

        public static Observation empty() {
            return new Observation(READS_KEPT);
        }

        /** The pool's: what no class's window held, with room for {@link #POOLED_READS_KEPT} data files. */
        static Observation pool() {
            return new Observation(POOLED_READS_KEPT);
        }

        /** Data files read, outside every keyed root, at most {@link #READS_KEPT}. */
        public Set<Path> reads() {
            return Collections.unmodifiableSet(reads);
        }

        /** Whether the class read more data files than its record names. */
        public boolean readsMore() {
            return readsMore;
        }

        /** Code files read by path (worker and plugin jars, a jar a test opens itself). */
        public Set<Path> code() {
            return Collections.unmodifiableSet(code);
        }

        public boolean codeMore() {
            return codeMore;
        }

        /** Workspace classes loaded from the class trees, by binary name. */
        public Set<String> loaded() {
            return Collections.unmodifiableSet(loaded);
        }

        public boolean loadedMore() {
            return loadedMore;
        }

        /**
         * Whether the marked class {@code binaryName}'s code ran in the window, or outside every
         * window. False for a class no mark was put in: what was used is known only where the probe
         * marked, so a class's own name among its uses says whether its JVM was marked at all.
         */
        public boolean used(String binaryName) {
            UseNames names = useNames;
            if (names == null) return false;
            Integer id = names.ids.get(binaryName);
            return id != null && used.get(id);
        }

        /** Whether a use went unnamed: the suite's uses named more classes than are kept. */
        public boolean usedMore() {
            return usedMore;
        }

        /** Programs started. */
        public Set<String> processes() {
            return Collections.unmodifiableSet(processes);
        }

        /**
         * Programs started that read nothing the suite's key does not hold but their own identity,
         * by name: git run in a repository a test made ({@link GitRuns}), a utility or a program the
         * test wrote whose command names nothing outside its temp roots ({@link OwnPrograms}).
         */
        public Set<String> tools() {
            return Collections.unmodifiableSet(tools);
        }

        /** Native libraries loaded from outside the JDK. */
        public Set<String> nativeCode() {
            return Collections.unmodifiableSet(nativeCode);
        }

        /** Reads of the operating system's own trees ({@code /proc}, {@code /etc}, …), a few. */
        public Set<Path> osPaths() {
            return Collections.unmodifiableSet(osPaths);
        }

        /** Hosts past this one the JVM's sockets reached. */
        public Set<String> hosts() {
            return Collections.unmodifiableSet(hosts);
        }

        public void addRead(Path p) {
            if (readsKept == READS_KEPT) {
                cover(p);
                name(p);
            }
            if (readsMore || reads.contains(p)) return;
            if (reads.size() < readsKept) reads.add(p);
            else readsMore = true;
        }

        /**
         * The directories that cover every data file the class read, at most {@link #ROOTS_KEPT};
         * empty past it ({@link #rootsMore}), when the class keys on every file its suite read.
         */
        public List<ReadRoot> readRoots() {
            return rootsMore ? List.of() : Collections.unmodifiableList(roots);
        }

        /**
         * The file names of every data file the class read, at most {@link #READ_NAMES_KEPT}; empty
         * past it, when its read roots alone bound what it stands on.
         */
        public Set<String> readNames() {
            return namesMore ? Set.of() : Collections.unmodifiableSet(names);
        }

        /** Whether the class's reads lie further apart than its read roots can say. */
        public boolean rootsMore() {
            return rootsMore;
        }

        /**
         * Cover {@code p} with the read roots: its directory joins them, and past {@link #ROOTS_KEPT}
         * the two roots with the deepest common directory become that directory, deep.
         */
        private void cover(Path p) {
            if (rootsMore || covered(p)) return;
            Path dir = p.getParent();
            if (dir == null) {
                rootsMore = true;
                return;
            }
            roots.add(new ReadRoot(dir, false));
            if (roots.size() <= ROOTS_KEPT) return;
            Path joined = null;
            for (int i = 0; i < roots.size(); i++) {
                for (int j = i + 1; j < roots.size(); j++) {
                    Path common = common(roots.get(i).dir(), roots.get(j).dir());
                    if (common != null && (joined == null || common.getNameCount() > joined.getNameCount())) {
                        joined = common;
                    }
                }
            }
            if (joined == null) {
                rootsMore = true;
                return;
            }
            ReadRoot deep = new ReadRoot(joined, true);
            roots.removeIf(r -> r.dir().startsWith(deep.dir()));
            roots.add(deep);
        }

        private void name(Path p) {
            Path file = p.getFileName();
            if (namesMore || file == null || names.contains(file.toString())) return;
            if (names.size() < READ_NAMES_KEPT) names.add(file.toString());
            else namesMore = true;
        }

        private boolean named(Path p) {
            Path file = p.getFileName();
            return file == null || names.contains(file.toString());
        }

        private boolean covered(Path p) {
            for (ReadRoot r : roots) if (r.covers(p)) return true;
            return false;
        }

        /** The deepest directory holding both {@code a} and {@code b}; null when they share no root. */
        private static @Nullable Path common(Path a, Path b) {
            Path c = a;
            while (c != null && !b.startsWith(c)) c = c.getParent();
            return c;
        }

        public void addCode(Path p) {
            if (codeMore || code.contains(p)) return;
            if (code.size() < CODE_KEPT) code.add(p);
            else codeMore = true;
        }

        /** Note a load, out of {@code budget}, the suite's loads left to keep. */
        void addLoaded(String binaryName, int[] budget) {
            if (loadedMore || loaded.contains(binaryName)) return;
            if (budget[0] > 0) {
                loaded.add(binaryName);
                budget[0]--;
            } else {
                loadedMore = true;
            }
        }

        /** Note a load, for a caller that keeps no budget of its own. */
        public void addLoaded(String binaryName) {
            addLoaded(binaryName, new int[] {1});
        }

        /** Note a use, numbered in {@code names}, the suite's. */
        void addUsed(String binaryName, UseNames names) {
            useNames = names;
            int id = names.id(binaryName);
            if (id < 0) usedMore = true;
            else used.set(id);
        }

        /** Note a use, for a caller that keeps no names of its own. */
        public void addUsed(String binaryName) {
            UseNames names = useNames;
            addUsed(binaryName, names == null ? new UseNames() : names);
        }

        /** Take in every use of {@code pool}, what no window held. */
        void addUses(Observation pool) {
            if (pool.useNames == null) return;
            useNames = pool.useNames;
            used.or(pool.used);
            usedMore |= pool.usedMore;
        }

        public void addProcess(String program) {
            bound = true;
            if (processes.size() < EVIDENCE_KEPT) processes.add(program);
        }

        /** Note a program keyed by its identity alone; past {@link #TOOLS_KEPT} names, a program started. */
        public void addTool(String program) {
            if (tools.contains(program)) return;
            if (tools.size() < TOOLS_KEPT) tools.add(program);
            else addProcess(program);
        }

        public void addNative(String library) {
            bound = true;
            if (nativeCode.size() < EVIDENCE_KEPT) nativeCode.add(library);
        }

        public void addOsPath(Path p) {
            if (!jvmOwn(p)) bound = true;
            if (osPaths.size() < EVIDENCE_KEPT && (!jvmOwn(p) || osPaths.size() < EVIDENCE_KEPT / 2)) osPaths.add(p);
        }

        public void addHost(String host) {
            if (hosts.size() < EVIDENCE_KEPT) hosts.add(host);
        }

        /**
         * Whether anything was observed that ties a result to the platform it ran on: a native
         * library, a program started, a system file other than those every JVM reads for itself
         * (its random seed, its own cgroup limits).
         */
        public boolean platformBound() {
            return bound;
        }

        /** Whether reading {@code p} would add to what this keeps. */
        boolean wantsRead(Path p) {
            return (!readsMore && !reads.contains(p))
                    || (readsKept == READS_KEPT && ((!rootsMore && !covered(p)) || (!namesMore && !named(p))));
        }
    }

    /** The classes a suite's uses name, numbered once for every class, at most {@link #USED_NAMES_KEPT}. */
    static final class UseNames {
        private final Map<String, Integer> ids = new HashMap<>();

        /** The number of {@code binaryName}, or -1 when the suite already names as many as are kept. */
        int id(String binaryName) {
            Integer known = ids.get(binaryName);
            if (known != null) return known;
            if (ids.size() >= USED_NAMES_KEPT) return -1;
            int id = ids.size();
            ids.put(binaryName, id);
            return id;
        }
    }

    /**
     * Every code file a suite's JVMs read that was still there once the run ended, at most {@link
     * #SUITE_CODE_KEPT}, and whether there were more.
     */
    public static final class SuiteCode {
        private final Set<Path> files = new TreeSet<>();
        private boolean more;

        public static SuiteCode empty() {
            return new SuiteCode();
        }

        public static SuiteCode of(Collection<Path> files) {
            SuiteCode code = new SuiteCode();
            files.forEach(code::add);
            return code;
        }

        public Set<Path> files() {
            return Collections.unmodifiableSet(files);
        }

        public boolean more() {
            return more;
        }

        void add(Path p) {
            if (more || files.contains(p)) return;
            if (files.size() < SUITE_CODE_KEPT) files.add(p);
            else more = true;
        }
    }

    /**
     * A suite's run, split by class.
     *
     * @param classes each top-level class that ran in a window, by binary name, or by {@link
     *     #launchKey} when every recording told its launch
     * @param pooled what no window held: every class's
     * @param code every code file the suite's JVMs read, which holds each class's and the pool's
     * @param complete false when a recording could not be read whole: no class of it is attributed
     * @param jvms how many recordings were read
     * @param events how many events were joined
     * @param launches what no window held in each launch's JVMs, by launch, when every recording
     *     told its launch; empty when one did not, or none was asked
     */
    public record Attributed(
            Map<String, Observation> classes,
            Observation pooled,
            SuiteCode code,
            boolean complete,
            int jvms,
            long events,
            Map<String, Observation> launches) {
        public static final Attributed NONE =
                new Attributed(Map.of(), Observation.empty(), SuiteCode.empty(), false, 0, 0, Map.of());

        /** A run whose JVMs read no code file outside what the suite's key holds. */
        public Attributed(
                Map<String, Observation> classes, Observation pooled, boolean complete, int jvms, long events) {
            this(classes, pooled, SuiteCode.empty(), complete, jvms, events, Map.of());
        }

        /**
         * What no window held in the JVMs of {@code launch}: its own pool where the run told the
         * launches apart, else every JVM's.
         */
        public Observation pooled(String launch) {
            return launches.getOrDefault(launch, pooled);
        }

        /**
         * What the windows of {@code testClass} held in the JVMs of {@code launch}, where the run told
         * its launches apart ({@link #launchKey}), else in every JVM; null when no window held it.
         */
        public @Nullable Observation of(String launch, String testClass) {
            Observation own = classes.get(launchKey(launch, testClass));
            return own != null ? own : classes.get(testClass);
        }
    }

    /**
     * The key of {@code testClass}'s observation in the JVMs of {@code launch}, in a run whose
     * recordings each told their launch: a class two launches run is two classes there, so what one
     * launch's windows did (start an engine) is no input of the other's row.
     */
    public static String launchKey(String launch, String testClass) {
        return launch + '|' + testClass;
    }

    /** One window: a class from its start to its finish, in epoch nanos. */
    record Window(String testClass, long start, long end) {}

    enum Kind {
        READ,
        WRITE,
        PROCESS,
        EXTERNAL,
        TOOL,
        NATIVE,
        HOST,
        USE
    }

    /** The first pass's events and fields: the class windows and the files the JVM wrote. */
    static final Map<String, List<String>> FRAME =
            Map.of(WINDOW_EVENT, List.of("testClass"), "jdk.FileWrite", List.of("path"));

    /**
     * The second pass's events and fields. Each event names its fields in the same order as in
     * {@link #FRAME}, so one dump of the JDK's parser serves both passes.
     */
    private static final Map<String, List<String>> EVENTS = Map.of(
            "jdk.FileRead",
            List.of("path"),
            "jdk.FileWrite",
            List.of("path"),
            "jdk.ProcessStart",
            List.of("command", "directory"),
            "jdk.NativeLibraryLoad",
            List.of("name", "success"),
            "jdk.SocketRead",
            List.of("host", "address", "eventThread"),
            "jdk.SocketWrite",
            List.of("host", "address", "eventThread"),
            USE_EVENT,
            List.of("className"));

    static final Map<String, List<String>> ALL;

    static {
        Map<String, List<String>> all = new LinkedHashMap<>(EVENTS);
        all.putAll(FRAME);
        ALL = Map.copyOf(all);
    }

    /** One pass over a recording: its events of {@code wanted}, in file order. */
    @FunctionalInterface
    interface Pass {
        void run(Map<String, List<String>> wanted, RecordingReader.Sink sink) throws IOException;
    }

    /**
     * Every recording under {@code dir}, relative paths resolved against {@code cwd}, read and joined.
     *
     * <p>A recording is read twice and never held: the first pass takes only the class windows (the
     * runner commits a window when its class finishes, so a window follows the events it holds) and
     * the files the JVM wrote; the second gives each event to its classes as it is read. What this
     * keeps is bounded by the classes and the distinct files, never by the event count: a long suite
     * records millions of file reads (jk-cli's, 6.2 million), and holding them all is what ran the
     * engine out of heap. Both passes stream through {@link RecordingReader}, which builds no
     * constant pool but the strings; a recording it declines is read by the JDK's own parser in a
     * child JVM ({@link RecordingDump}), whose lines both passes then stream.
     */
    public static Attributed attribute(Path dir, Path cwd, Layout layout) {
        return attribute(dir, cwd, layout, classes -> null);
    }

    /**
     * As {@link #attribute(Path, Path, Layout)}, each recording's pool also kept as the pool
     * of its launch: the launch {@code launchOf} names for the classes its windows hold, null when it
     * cannot tell one. A launch runs its own test JVMs, so what one launch's JVMs did outside every
     * window is no input of another launch's classes, nor what a class's window did there an input of
     * the same class's window in another launch ({@link #launchKey}). One recording whose launch is
     * not told leaves no launch's pool and keys no class by launch: each stands on every JVM's
     * ({@link Attributed#pooled(String)}).
     */
    public static Attributed attribute(
            Path dir, Path cwd, Layout layout, Function<Collection<String>, @Nullable String> launchOf) {
        List<Path> recordings = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().endsWith(".jfr")) recordings.add(child);
                return true;
            });
        } catch (IOException none) {
            return Attributed.NONE;
        }
        Map<String, Observation> classes = new LinkedHashMap<>();
        int[] loadedLeft = {LOADED_KEPT};
        Observation pooled = Observation.pool();
        Map<String, Observation> launches = new HashMap<>();
        UseNames useNames = new UseNames();
        PathVerdicts verdicts = new PathVerdicts(cwd, layout);
        SuiteCode code = SuiteCode.empty();
        boolean complete = true;
        long events = 0;
        List<FramedRecording> framed = new ArrayList<>();
        try {
            // Every recording's windows first: whether each tells its launch decides every class's key.
            for (Path jfr : recordings) {
                try {
                    FramedRecording f = FramedRecording.read(jfr, verdicts);
                    framed.add(f.told(launchOf.apply(
                            f.windows().stream().map(Window::testClass).toList())));
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("class reads: recording unreadable at " + jfr, unreadable);
                    complete = false;
                }
            }
            boolean launchesTold = framed.stream().allMatch(f -> f.launch() != null);
            for (FramedRecording f : framed) {
                try {
                    List<Observation> pools = new ArrayList<>(2);
                    pools.add(pooled);
                    List<Window> windows = f.windows();
                    String launch = f.launch();
                    if (launchesTold && launch != null) {
                        pools.add(launches.computeIfAbsent(launch, l -> Observation.pool()));
                        windows = windows.stream()
                                .map(w -> new Window(launchKey(launch, w.testClass()), w.start(), w.end()))
                                .toList();
                    }
                    Join join = new Join(
                            windows, f.written(), cwd, layout, verdicts, classes, pools, code, loadedLeft, useNames);
                    try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
                        f.pass().run(EVENTS, (name, start, end, v) -> read(name, start, v, join));
                    }
                    join.finish();
                    events += join.events;
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("class reads: recording unreadable at " + f.jfr(), unreadable);
                    complete = false;
                }
            }
            return new Attributed(
                    classes, pooled, code, complete, recordings.size(), events, launchesTold ? launches : Map.of());
        } finally {
            for (FramedRecording f : framed) f.removeDump();
        }
    }

    /** The second pass: one event, given to its classes as it is read. */
    private static void read(String name, long at, @Nullable String[] v, Join join) {
        switch (name) {
            case "jdk.FileRead" -> join.accept(at, Kind.READ, v[0]);
            case "jdk.FileWrite" -> join.accept(at, Kind.WRITE, v[0]);
            case USE_EVENT -> join.accept(at, Kind.USE, v[0]);
            case "jdk.ProcessStart" -> {
                // A test harness's own command around every class (CheckoutUntouched's git status)
                // is no program the class started.
                if (v[0] == null || v[0].contains(GitCommand.HARNESS)) return;
                if (GitRuns.ownRepository(v[0], v[1], join.cwd, join.gitTemp)) {
                    join.accept(at, Kind.TOOL, GitRuns.GIT);
                    return;
                }
                String tool = OwnPrograms.tool(v[0], v[1], join.cwd, join.gitTemp, join.path);
                if (tool != null) {
                    join.accept(at, Kind.TOOL, tool);
                } else if (ClassPathCommands.reaches(v[0], v[1], join.cwd, join.layout)) {
                    join.accept(at, Kind.PROCESS, program(v[0]));
                } else {
                    join.accept(at, Kind.EXTERNAL, program(v[0]));
                    for (Path named : ClassPathCommands.named(v[0], v[1], join.cwd, join.layout))
                        join.accept(at, Kind.READ, named.toString());
                }
            }
            case "jdk.NativeLibraryLoad" -> {
                // A failed load (JDK 22 and later say so) loaded nothing.
                if (v[1] != null && !Boolean.parseBoolean(v[1])) return;
                join.accept(at, Kind.NATIVE, v[0]);
            }
            case "jdk.SocketRead", "jdk.SocketWrite" -> {
                if (!local(v[1]))
                    join.host(at, DiscoveryRules.hostName(v[0], v[1]), v[2], name.equals("jdk.SocketRead"));
            }
            default -> {}
        }
    }

    /**
     * One JVM's events given to the classes whose window holds each, or to the pool, one at a time. A
     * file the JVM wrote is its own output: a read of it is no input.
     */
    static final class Join {
        private final List<Window> windows;
        private final long[] starts;
        private final long[] reach; // the latest end of any window up to i
        private final Set<Path> written;
        private final Path cwd;
        /** The temp roots a git command may run in and still read only what a test made. */
        private final List<Path> gitTemp;
        /** The {@code PATH} a program's bare name is looked up on. */
        private final @Nullable String path = BuildEnv.machine().get("PATH");

        private final Layout layout;
        private final PathVerdicts verdicts;
        private final Map<String, Observation> classes;
        /** What no window holds goes to each: the suite's pool, and its launch's where told. */
        private final List<Observation> pools;

        /**
         * Each thread that reached another host, by its id, with the classes whose window held its
         * first such event; at most {@value #NETWORK_THREADS_KEPT}.
         */
        private final Map<String, List<Observation>> networkThreads = new HashMap<>();

        /** The uses no window of this JVM held, which every class it ran may have read the state of. */
        private final Observation unheldUses = Observation.empty();

        private final SuiteCode suiteCode;
        /** Whether each recently asked path is there, the last {@value #EXISTS_KEPT} asked. */
        private final Map<Path, Boolean> exists = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, Boolean> eldest) {
                return size() > EXISTS_KEPT;
            }
        };

        private boolean codeOverflow;
        /** The suite's loads left to keep, shared by its JVMs' joins. */
        private final int[] loadedLeft;

        /** The classes the suite's uses name, shared by its JVMs' joins. */
        private final UseNames useNames;

        /** One instance of each class name loaded, shared by every class that loaded it. */
        private final Map<String, String> names = new HashMap<>();
        /** Each code file read, by the first and the last time it was read. */
        private final Map<Path, long[]> codeReads = new LinkedHashMap<>();
        /** When the JVM first started a program git in a test's own repository is not ({@link #STARTED_PROGRAM}). */
        private long firstStart = Long.MAX_VALUE;

        long events;

        Join(
                List<Window> windows,
                Set<Path> written,
                Path cwd,
                Layout layout,
                PathVerdicts verdicts,
                Map<String, Observation> classes,
                List<Observation> pools,
                SuiteCode suiteCode,
                int[] loadedLeft,
                UseNames useNames) {
            this.verdicts = verdicts;
            this.loadedLeft = loadedLeft;
            this.useNames = useNames;
            this.suiteCode = suiteCode;
            this.windows = new ArrayList<>(windows);
            this.windows.sort(Comparator.comparingLong(Window::start));
            this.written = written;
            this.cwd = cwd;
            this.gitTemp = GitRuns.unenclosed(layout.temp());
            this.layout = layout;
            this.classes = classes;
            this.pools = pools;
            int n = this.windows.size();
            starts = new long[n];
            reach = new long[n];
            for (int i = 0; i < n; i++) {
                Window w = this.windows.get(i);
                starts[i] = w.start();
                reach[i] = Math.max(w.end(), i > 0 ? reach[i - 1] : Long.MIN_VALUE);
                of(classes, w.testClass());
            }
        }

        /**
         * A socket event to {@code host} on {@code thread}. A read on a thread whose first such event
         * an earlier class's window held is that class's: a connection a class left open is read
         * from when the server closes it, minutes later, in whichever window or gap is open then, and
         * a class that sends a request on it writes in its own window. Every write goes by its time.
         */
        void host(long at, String host, @Nullable String thread, boolean read) {
            if (thread == null) {
                accept(at, Kind.HOST, host);
                return;
            }
            List<Observation> first = networkThreads.get(thread);
            if (first == null) {
                List<Observation> held = held(at);
                if (held != null && !held.isEmpty() && networkThreads.size() < NETWORK_THREADS_KEPT) {
                    networkThreads.put(thread, held);
                }
            } else if (read && !first.equals(held(at))) {
                events++;
                first.forEach(o -> o.addHost(host));
                return;
            }
            accept(at, Kind.HOST, host);
        }

        /**
         * The observations of the classes whose window holds {@code at}, empty when every one of them
         * is past the suite's bound; null when no window holds it.
         */
        private @Nullable List<Observation> held(long at) {
            List<Observation> held = null;
            for (int i = lastStartingBy(starts, at); i >= 0 && reach[i] >= at; i--) {
                Window w = windows.get(i);
                if (w.end() < at) continue;
                if (held == null) held = new ArrayList<>(1);
                Observation o = classes.get(w.testClass());
                if (o != null) held.add(o);
            }
            return held;
        }

        void accept(long at, Kind kind, @Nullable String value) {
            if (value == null || value.isBlank()) return;
            events++;
            if (kind == Kind.WRITE) return;
            List<Observation> into = new ArrayList<>(2);
            boolean inWindow = false;
            for (int i = lastStartingBy(starts, at); i >= 0 && reach[i] >= at; i--) {
                Window w = windows.get(i);
                if (w.end() < at) continue;
                inWindow = true;
                Observation o = classes.get(w.testClass());
                // A class past the suite's bound keeps nothing and is keyed by its module.
                if (o != null) into.add(o);
            }
            if (inWindow && into.isEmpty()) return;
            if (into.isEmpty()) into.addAll(pools);
            switch (kind) {
                case PROCESS -> {
                    // The test JDK's own launcher (an agent attaching from a second JVM, as Mockito's
                    // does) is the JDK the key already holds, not a program the class depends on.
                    firstStart = Math.min(firstStart, at);
                    if (!underJdk(value, layout)) into.forEach(o -> o.addProcess(value));
                }
                case EXTERNAL -> {
                    firstStart = Math.min(firstStart, at);
                    if (!underJdk(value, layout)) into.forEach(o -> o.addTool(EXTERNAL_PROGRAM));
                }
                case TOOL -> {
                    if (!value.equals(GitRuns.GIT)) firstStart = Math.min(firstStart, at);
                    into.forEach(o -> o.addTool(value));
                }
                case HOST -> into.forEach(o -> o.addHost(value));
                case USE -> {
                    into.forEach(o -> o.addUsed(value, useNames));
                    if (!inWindow) unheldUses.addUsed(value, useNames);
                }
                case NATIVE -> {
                    if (!underJdk(value, layout)) into.forEach(o -> o.addNative(value));
                }
                case READ -> {
                    PathVerdicts.Verdict read = verdicts.of(value);
                    if (read == null) return;
                    Path p = read.path();
                    if (written.contains(p)) return;
                    switch (where(read)) {
                        case LOADED -> {
                            String cls = read.className();
                            if (cls != null) {
                                String one = names.computeIfAbsent(cls, c -> c);
                                into.forEach(o -> o.addLoaded(one, loadedLeft));
                            }
                        }
                        case OS -> into.forEach(o -> o.addOsPath(p));
                        case CODE -> {
                            if (!codeReads.containsKey(p) && codeReads.size() >= CODE_FILES_KEPT) {
                                codeOverflow = true;
                                return;
                            }
                            long[] span = codeReads.computeIfAbsent(p, k -> new long[] {at, at});
                            span[0] = Math.min(span[0], at);
                            span[1] = Math.max(span[1], at);
                        }
                        case DATA -> {
                            // A file gone once the run ended was made and removed by it: no input.
                            if (into.stream().anyMatch(o -> o.wantsRead(p)) && exists(p))
                                into.forEach(o -> o.addRead(p));
                        }
                        case KEYED -> {}
                    }
                }
                case WRITE -> {}
            }
        }

        /**
         * A code file read by the time a class finished is one its JVM's loaders may serve it from:
         * the classes a file's first read reaches are every class any read of it reaches, and it is
         * the pool's when its last read came after every window closed. One gone once the run ended
         * is none of them.
         */
        /** The code files already judged, by their verdict under their real path, the last few thousand. */
        private final Map<Path, Where> code = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, Where> eldest) {
                return size() > EXISTS_KEPT;
            }
        };

        /** Where {@code read} lies; a code file is judged again under its real path, once. */
        private Where where(PathVerdicts.Verdict read) {
            if (read.where() != Where.CODE) return read.where();
            return code.computeIfAbsent(read.path(), f -> {
                Path r = real(f);
                return r.equals(f) ? Where.CODE : classify(r, layout);
            });
        }

        private boolean exists(Path p) {
            return exists.computeIfAbsent(p, Files::exists);
        }

        void finish() {
            if (codeOverflow) {
                // More code files than are kept: no class can name what it may have loaded.
                for (Window w : windows) {
                    Observation o = classes.get(w.testClass());
                    if (o != null) o.codeMore = true;
                }
                for (Observation p : pools) p.codeMore = true;
                suiteCode.more = true;
            }
            long lastEnd = Long.MIN_VALUE;
            for (Window w : windows) lastEnd = Math.max(lastEnd, w.end());
            // A program outlives the window that started it, as a loaded jar does: every later class
            // of the JVM may talk to what it runs. A use outside every window (discovery, a static
            // initialiser) leaves state any class of the JVM may read, and none of another JVM's.
            for (Window w : windows) {
                Observation o = classes.get(w.testClass());
                if (o == null) continue;
                if (firstStart <= w.end()) o.addTool(STARTED_PROGRAM);
                o.addUses(unheldUses);
            }
            for (Map.Entry<Path, long[]> c : codeReads.entrySet()) {
                // A code file gone once the run ended was made and removed by it (a jar a test wrote
                // in its temp directory): no input, and no load a later class may be served from.
                if (!exists(c.getKey())) continue;
                suiteCode.add(c.getKey());
                long first = c.getValue()[0];
                for (Window w : windows) {
                    Observation o = classes.get(w.testClass());
                    if (first <= w.end() && o != null) o.addCode(c.getKey());
                }
                if (c.getValue()[1] > lastEnd) pools.forEach(p -> p.addCode(c.getKey()));
            }
            codeReads.clear();
        }
    }

    /** The class's observation, made for it unless the suite already has {@link #CLASSES_KEPT}. */
    private static void of(Map<String, Observation> classes, String testClass) {
        if (classes.size() < CLASSES_KEPT) classes.computeIfAbsent(testClass, c -> Observation.empty());
    }

    /** The index of the last window starting at or before {@code at}, or -1. */
    private static int lastStartingBy(long[] starts, long at) {
        int lo = 0;
        int hi = starts.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= at) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** What a read path is to the class that read it. */
    enum Where {
        LOADED,
        OS,
        CODE,
        KEYED,
        DATA
    }

    static Where classify(Path p, Layout layout) {
        String name = String.valueOf(p.getFileName());
        if (name.endsWith(".class") && under(p, layout.classTrees())) return Where.LOADED;
        if (under(p, layout.scratch())) return Where.KEYED;
        // jk's own probe agent, on every probed test JVM: the suite's key holds the code that cut it.
        if (DiscoveryRules.probeJar(p)) return Where.KEYED;
        if (osPath(p)) return Where.OS;
        if (layout.javaHome() != null && p.startsWith(layout.javaHome())) return Where.KEYED;
        // What the suite's key holds whole (the classpath, the test runner, the plugin workers it
        // stamps) is no class's own, code or not.
        if (under(p, layout.keyed()) || layout.externalJars().contains(p)) return Where.KEYED;
        if (codeFile(name)) return Where.CODE;
        return Where.DATA;
    }

    private static boolean codeFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".jmod") || n.endsWith(".class");
    }

    /**
     * Whether {@code p} lies in the operating system's own trees: the live pseudo trees and the
     * system configuration on Linux, the system and library trees on macOS, the Windows directory.
     */
    static boolean osPath(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String prefix : OS_PREFIXES) {
            if (s.startsWith(prefix)) return true;
        }
        String win = ProcessEnv.get("SystemRoot");
        return win != null
                && !win.isBlank()
                && s.toLowerCase(Locale.ROOT).startsWith(win.replace('\\', '/').toLowerCase(Locale.ROOT) + "/");
    }

    /** Whether {@code p} is a system file every JVM reads for itself, whatever its code does. */
    static boolean jvmOwn(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String prefix : JVM_OWN) {
            if (s.equals(prefix) || (prefix.endsWith("/") && s.startsWith(prefix))) return true;
        }
        return false;
    }

    private static final List<String> JVM_OWN =
            List.of("/dev/random", "/dev/urandom", "/dev/null", "/proc/self/", "/proc/cgroups", "/sys/fs/cgroup/");

    private static final List<String> OS_PREFIXES =
            List.of("/proc/", "/sys/", "/dev/", "/etc/", "/System/", "/Library/", "/private/etc/");

    private static boolean underJdk(String library, Layout layout) {
        if (layout.javaHome() == null) return false;
        try {
            Path lib = Path.of(library).toAbsolutePath().normalize();
            return lib.startsWith(layout.javaHome()) || real(lib).startsWith(real(layout.javaHome()));
        } catch (InvalidPathException bare) {
            return false;
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException gone) {
            return p;
        }
    }

    private static boolean under(Path p, Collection<Path> roots) {
        for (Path r : roots) {
            if (p.startsWith(r)) return true;
        }
        return false;
    }

    private static boolean local(@Nullable String address) {
        if (address == null || address.isBlank() || address.startsWith("[")) return true;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /**
     * The program of a recorded command line, as evidence a process started: its first word, or the
     * second past a leading {@code setsid}.
     */
    private static @Nullable String program(@Nullable String command) {
        if (command == null) return null;
        return HeapFlags.executable(List.of(command.strip().split("\\s+", 3)));
    }

    private static List<Path> withReal(List<Path> paths) {
        Set<Path> out = new LinkedHashSet<>(paths);
        for (Path p : paths) out.add(real(p));
        return List.copyOf(out);
    }

    private static List<Path> normalized(Collection<Path> paths) {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }
}
