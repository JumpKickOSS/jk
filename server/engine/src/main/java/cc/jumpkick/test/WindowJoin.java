// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static cc.jumpkick.test.ClassReads.CODE_FILES_KEPT;
import static cc.jumpkick.test.ClassReads.EXISTS_KEPT;
import static cc.jumpkick.test.ClassReads.EXTERNAL_PROGRAM;
import static cc.jumpkick.test.ClassReads.NETWORK_THREADS_KEPT;
import static cc.jumpkick.test.ClassReads.READ_THREADS_KEPT;
import static cc.jumpkick.test.ClassReads.STARTED_PROGRAM;
import static cc.jumpkick.test.ClassReads.WORKER_JARS_KEPT;
import static cc.jumpkick.test.ClassReads.classify;
import static cc.jumpkick.test.ClassReads.lastStartingBy;
import static cc.jumpkick.test.ClassReads.of;
import static cc.jumpkick.test.ClassReads.real;
import static cc.jumpkick.test.ClassReads.underJdk;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.test.ClassReads.Handed;
import cc.jumpkick.test.ClassReads.Kind;
import cc.jumpkick.test.ClassReads.Layout;
import cc.jumpkick.test.ClassReads.Observation;
import cc.jumpkick.test.ClassReads.SuiteCode;
import cc.jumpkick.test.ClassReads.UseNames;
import cc.jumpkick.test.ClassReads.Where;
import cc.jumpkick.test.ClassReads.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * One JVM's events given to the classes whose window holds each, or to the pool, one at a time. A
 * file the JVM wrote is its own output: a read of it is no input.
 */
final class WindowJoin {
    private final List<Window> windows;
    private final long[] starts;
    private final long[] reach; // the latest end of any window up to i
    private final Set<Path> written;
    final Path cwd;
    /** The temp roots a git command may run in and still read only what a test made. */
    final List<Path> gitTemp;
    /** The {@code PATH} a program's bare name is looked up on. */
    final @Nullable String path = BuildEnv.machine().get("PATH");

    final Layout layout;
    private final PathVerdicts verdicts;
    private final Map<String, Observation> classes;
    /** What no window holds goes to each: the suite's pool, and its launch's where told. */
    private final List<Observation> pools;

    /**
     * Each thread that reached another host, by its id, with the time of its earliest such event a
     * window held and the classes whose window held it; at most {@value ClassReads#NETWORK_THREADS_KEPT}.
     */
    private final Map<String, FirstRead> networkThreads = new HashMap<>();

    /** The uses no window of this JVM held, which every class it ran may have read the state of. */
    private final Observation unheldUses = Observation.empty();

    private final SuiteCode suiteCode;
    /** Whether each recently asked path is there, the last {@value ClassReads#EXISTS_KEPT} asked. */
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
    /** When the JVM first started a program git in a test's own repository is not ({@link ClassReads#STARTED_PROGRAM}). */
    private long firstStart = Long.MAX_VALUE;

    /** The classes whose window started such a program. */
    private final Set<Observation> startedIn = new HashSet<>();

    /** The classes whose window reached a local peer: a socket to this host not the run's own, a pipe. */
    private final Set<Observation> reached = new HashSet<>();

    /** The run's own loopback ports ({@link Handed#ownPorts}). */
    private final Set<Integer> ownPorts;

    /** The worker jars by number, at most {@value ClassReads#WORKER_JARS_KEPT}, and each number by both spellings. */
    private final List<Path> workerJars;

    private final Map<Path, Integer> workerIds = new HashMap<>();

    /**
     * Each thread that read a file, by its id, with the time of its earliest read seen and the
     * classes whose window held it, empty when none did; at most {@value ClassReads#READ_THREADS_KEPT}.
     * A recording lists a thread's events out of time order, so the earliest is kept, not the first listed.
     */
    private final Map<String, FirstRead> readThreads = new HashMap<>();

    private record FirstRead(long at, List<Observation> owners) {}

    /** A worker jar read outside every window on a thread, judged once every read of the thread is in. */
    private record ThreadWorker(int id, String thread) {}

    /** The first and last time each such read happened. */
    private final Map<ThreadWorker, long[]> threadWorkerReads = new HashMap<>();

    /** Each worker jar read outside every window on a thread no window owns, by the first and last time. */
    private final Map<Integer, long[]> unownedWorkerReads = new HashMap<>();

    long events;

    WindowJoin(
            List<Window> windows,
            Set<Path> written,
            Path cwd,
            Layout layout,
            PathVerdicts verdicts,
            Map<String, Observation> classes,
            List<Observation> pools,
            SuiteCode suiteCode,
            int[] loadedLeft,
            UseNames useNames,
            Handed handed) {
        this.verdicts = verdicts;
        this.ownPorts = handed.ownPorts();
        List<Path> jars = new ArrayList<>(new TreeSet<>(handed.workerJars()));
        this.workerJars = List.copyOf(jars.subList(0, Math.min(jars.size(), WORKER_JARS_KEPT)));
        for (int i = 0; i < workerJars.size(); i++) {
            workerIds.put(workerJars.get(i), i);
            workerIds.putIfAbsent(real(workerJars.get(i)), i);
        }
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

    /** Whether {@code port}, as a recording renders it, is one of the run's own. */
    boolean ownPort(@Nullable String port) {
        if (port == null || ownPorts.isEmpty()) return false;
        try {
            return ownPorts.contains(Integer.parseInt(port));
        } catch (NumberFormatException none) {
            return false;
        }
    }

    /** Traffic with a local peer at {@code at}: a class whose window holds it may talk to a program. */
    void peer(long at) {
        events++;
        List<Observation> held = held(at);
        if (held != null) reached.addAll(held);
    }

    /** A file read at {@code at} on {@code thread}. */
    void read(long at, String path, @Nullable String thread) {
        if (thread != null) {
            FirstRead known = readThreads.get(thread);
            if ((known == null && readThreads.size() < READ_THREADS_KEPT) || (known != null && at < known.at())) {
                List<Observation> held = held(at);
                readThreads.put(thread, new FirstRead(at, held == null ? List.of() : held));
            }
        }
        accept(at, Kind.READ, path, thread);
    }

    /**
     * A socket event to {@code host} on {@code thread}. A read on a thread whose first such event
     * an earlier class's window held is that class's: a connection a class left open is read
     * from when the server closes it, minutes later, in whichever window or gap is open then, and
     * a class that sends a request on it writes in its own window. Every write goes by its time.
     * A recording lists a thread's events out of time order, so a read is judged at {@link #finish}.
     */
    void host(long at, String host, @Nullable String thread, boolean read) {
        if (thread == null) {
            accept(at, Kind.HOST, host);
            return;
        }
        List<Observation> held = held(at);
        FirstRead first = networkThreads.get(thread);
        if (held != null && !held.isEmpty()) {
            if ((first == null && networkThreads.size() < NETWORK_THREADS_KEPT) || (first != null && at < first.at())) {
                networkThreads.put(thread, new FirstRead(at, held));
            }
        }
        if (read) {
            long[] span = hostReads.computeIfAbsent(new HostRead(thread, host, held), k -> new long[] {at, at});
            span[0] = Math.min(span[0], at);
            span[1] = Math.max(span[1], at);
        } else {
            accept(at, Kind.HOST, host);
        }
    }

    /** A socket read of {@code host} on {@code thread} by the classes whose window held it, null when none did. */
    private record HostRead(
            String thread, String host, @Nullable List<Observation> held) {}

    /** The first and last time each such read happened. */
    private final Map<HostRead, long[]> hostReads = new LinkedHashMap<>();

    private void judgeHostReads() {
        for (Map.Entry<HostRead, long[]> e : hostReads.entrySet()) {
            HostRead r = e.getKey();
            long first = e.getValue()[0];
            long last = e.getValue()[1];
            FirstRead owner = networkThreads.get(r.thread());
            if (owner == null || owner.owners().equals(r.held()) || last <= owner.at()) {
                accept(first, Kind.HOST, r.host());
                continue;
            }
            if (first <= owner.at()) accept(first, Kind.HOST, r.host());
            events++;
            owner.owners().forEach(o -> o.addHost(r.host()));
        }
        hostReads.clear();
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
        accept(at, kind, value, null);
    }

    private void accept(long at, Kind kind, @Nullable String value, @Nullable String thread) {
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
                // The test JDK's own launcher is the JDK the key already holds, not a program the
                // class depends on; but what it runs of the test class path no window marks, so
                // the class keys its whole cone, not only what its window ran.
                started(at, inWindow, into);
                if (!underJdk(value, layout)) {
                    into.forEach(o -> o.addProcess(value));
                } else {
                    into.forEach(Observation::ranUnmarked);
                    if (!inWindow) unheldUses.ranUnmarked();
                }
            }
            case EXTERNAL -> {
                started(at, inWindow, into);
                if (!underJdk(value, layout)) into.forEach(o -> o.addTool(EXTERNAL_PROGRAM));
            }
            case TOOL -> {
                if (!value.equals(GitRuns.GIT)) started(at, inWindow, into);
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
                        Integer worker = workerId(p);
                        if (worker != null) {
                            worker(at, worker, inWindow, into, thread);
                            return;
                        }
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
                        if (into.stream().anyMatch(o -> o.wantsRead(p)) && exists(p)) into.forEach(o -> o.addRead(p));
                    }
                    case KEYED -> {}
                }
            }
            case WRITE -> {}
        }
    }

    private void started(long at, boolean inWindow, List<Observation> into) {
        firstStart = Math.min(firstStart, at);
        if (inWindow) startedIn.addAll(into);
    }

    private @Nullable Integer workerId(Path p) {
        if (workerIds.isEmpty()) return null;
        Integer id = workerIds.get(p);
        return id != null ? id : workerIds.get(real(p));
    }

    /**
     * A worker jar read: the classes whose window holds the read; outside every window, those whose
     * window held the reading thread's first read, else every class whose window had not closed
     * by then and, read after every window, the pool.
     */
    private void worker(long at, int id, boolean inWindow, List<Observation> into, @Nullable String thread) {
        if (inWindow) {
            into.forEach(o -> o.addWorker(id, workerJars));
        } else if (thread != null && readThreads.containsKey(thread)) {
            long[] span = threadWorkerReads.computeIfAbsent(new ThreadWorker(id, thread), k -> new long[] {at, at});
            span[0] = Math.min(span[0], at);
            span[1] = Math.max(span[1], at);
        } else {
            unowned(at, at, id);
        }
    }

    private void unowned(long first, long last, int id) {
        long[] span = unownedWorkerReads.computeIfAbsent(id, k -> new long[] {first, last});
        span[0] = Math.min(span[0], first);
        span[1] = Math.max(span[1], last);
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
        judgeHostReads();
        if (codeOverflow) {
            // More code files than are kept: no class can name what it may have loaded.
            for (Window w : windows) {
                Observation o = classes.get(w.testClass());
                if (o != null) o.markCodeMore();
            }
            for (Observation p : pools) p.markCodeMore();
            suiteCode.markMore();
        }
        long lastEnd = Long.MIN_VALUE;
        for (Window w : windows) lastEnd = Math.max(lastEnd, w.end());
        // A program outlives the window that started it: a later class of the JVM whose window
        // reached a local peer may have talked to what it runs. A use outside every window
        // (discovery, a static initialiser) leaves state any class of the JVM may read, and none
        // of another JVM's.
        for (Window w : windows) {
            Observation o = classes.get(w.testClass());
            if (o == null) continue;
            if (startedIn.contains(o) || (firstStart <= w.end() && reached.contains(o))) o.addTool(STARTED_PROGRAM);
            o.addUses(unheldUses);
        }
        for (Map.Entry<ThreadWorker, long[]> t : threadWorkerReads.entrySet()) {
            ThreadWorker key = t.getKey();
            FirstRead first = readThreads.get(key.thread());
            if (first != null && !first.owners().isEmpty()) {
                first.owners().forEach(o -> o.addWorker(key.id(), workerJars));
            } else {
                unowned(t.getValue()[0], t.getValue()[1], key.id());
            }
        }
        threadWorkerReads.clear();
        for (Map.Entry<Integer, long[]> r : unownedWorkerReads.entrySet()) {
            int id = r.getKey();
            for (Window w : windows) {
                Observation o = classes.get(w.testClass());
                if (r.getValue()[0] <= w.end() && o != null) o.addWorker(id, workerJars);
            }
            if (r.getValue()[1] > lastEnd) pools.forEach(p -> p.addWorker(id, workerJars));
        }
        unownedWorkerReads.clear();
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
