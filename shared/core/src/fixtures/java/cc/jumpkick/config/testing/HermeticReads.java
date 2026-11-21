// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import cc.jumpkick.host.GitConfigFiles;
import cc.jumpkick.testing.CheckoutUntouched;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.util.KeychainSandbox;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.EventSettings;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class whose JVM read, wrote or ran {@code git} in what a suite of jk's own may not
 * touch: the developer's real jk home, the {@code .git} of the checkout the suite runs from, and
 * the user's and the system's git configuration, and the user's Maven repository and settings;
 * and one that reached for the user's OS keychain ({@link #keychain}).
 * Before each class it points jk's Maven user configuration into the sandbox ({@link #sandboxMaven}).
 *
 * <p>Each of these is outside what a suite's key names. A read of the checkout's {@code .git} made
 * the suite's discovered inputs name {@code .git/config}, so a {@code git worktree add} or a branch
 * made elsewhere in the repository re-ran it; a read of another home's engine endpoint
 * re-ran it whenever any engine on the machine started; a read of {@code ~/.gitconfig}
 * makes a commit in a fixture repository depend on whoever runs the suite. jk already gives every
 * test JVM a home of its own under {@code <home>/test-homes} and points git's global configuration
 * at it; a test reaching past that is a test to fix, not an input to key.
 *
 * <p>Watched in process, through one JFR stream with stack traces for the JVM's life ({@link
 * Watcher}): {@code jdk.FileRead} and {@code jdk.FileWrite} by path, and {@code jdk.ProcessStart}
 * for a {@code git} started inside the checkout. An event belongs to the class whose window it
 * happened in. JFR delivers events about a second late, so a class is judged at its end on what has
 * arrived, and what arrives later fails the next class to finish, naming the class that did it; what
 * the JVM's last classes did is judged at its exit, which then ends non-zero. The failure names each
 * path once; under {@code -Djk.test.hermetic=frames} also the first frames of jk's code that reached
 * it (off by default: a stack trace on every file event makes the suite's own recording, which the
 * engine parses after the run, several times larger).
 *
 * <p>Only this JVM's own reads are seen: a {@code git} child reads its configuration where its
 * environment points it, which jk's test environment sets to the sandbox ({@code GIT_CONFIG_GLOBAL},
 * {@code GIT_CONFIG_NOSYSTEM}); in process, JGit is pointed there before each class ({@link
 * #sandboxJGit}).
 *
 * <p>The keychain is judged on two signs. A backend over the real Secret Service, macOS Keychain
 * or Windows Credential Manager refuses in a test JVM ({@link KeychainSandbox}), and the class that
 * made it refuse fails at its end even when the code under test swallowed the error; and a keychain
 * tool started from this JVM ({@code secret-tool}, {@code security}, a {@code busctl} or {@code
 * gdbus} call to {@code org.freedesktop.secrets}) is caught like a {@code git} in the checkout.
 *
 * <p>Off outside a jk test sandbox (no {@code test-homes} in {@code JK_HOME}): the real home is then
 * unknown. {@code -Djk.test.hermetic=off} turns it off for one run. {@link CheckoutUntouched}'s own
 * {@code git status} of the checkout is the one deliberate exception.
 */
public final class HermeticReads implements BeforeAllCallback, AfterAllCallback {

    /** The system property that turns the check off ({@code off}). */
    public static final String PROPERTY = "jk.test.hermetic";

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(HermeticReads.class);

    private static final String WATCH = "watch";

    /** Paths named in one failure; the rest are counted. */
    static final int SHOWN = 8;

    /** jk frames shown per path. */
    static final int FRAMES = 6;

    /**
     * What a suite may not touch, each with the trees under it that it may: the real home less its
     * test sandboxes and the shared test pool, the checkout's git directories, git's configuration
     * outside the sandbox.
     */
    record Fence(
            List<Path> denied,
            List<Path> allowed,
            List<Path> readable,
            List<Path> own,
            @Nullable Path checkout) {

        /**
         * Whether {@code path} lies in a denied tree and in none of the allowed ones, or is an
         * engine's state ({@code …/state/engine/…}) of a home that is not this JVM's own: its
         * sandbox slot or its temp roots, where a test's nested engines live.
         */
        boolean denies(Path path) {
            if (under(path, denied) && !under(path, allowed)) return true;
            return isEngineState(path) && !under(path, own);
        }

        /** Whether reading {@code path} is denied: {@link #denies}, less the trees still read on purpose. */
        boolean deniesRead(Path path) {
            return denies(path) && !under(path, readable);
        }

        /** Whether a {@code git} started in {@code directory} with {@code command} reads the checkout's repository. */
        boolean deniesGit(@Nullable String directory, String command) {
            if (checkout == null || !isGit(command)) return false;
            Path dir = directory == null || directory.isBlank() ? null : path(directory);
            Path target = gitTarget(command, dir);
            return target != null && under(target, List.of(checkout));
        }
    }

    /** One class's run in this JVM: when it started and ended, and what was caught inside it. */
    static final class Window {
        final String testClass;
        final Instant start;

        @Nullable
        Instant end;

        final Map<String, String> caught = new LinkedHashMap<>();
        boolean reported;

        Window(String testClass, Instant start) {
            this.testClass = testClass;
            this.start = start;
        }
    }

    /**
     * The one stream this JVM watches through, started at the first class and never stopped, and
     * every class's window. Starting or stopping any JFR recording rotates the chunk of every
     * recording running in the JVM, the suite's own among them, and each chunk repeats the event
     * metadata: a stream per class made jk-engine's suite recording 831 chunks and 155 MB for 12 MB
     * of events, nine times main's, and the engine parses it after the suite. One stream
     * for the JVM's life leaves the suite recording at its two chunks; a class's events are told
     * apart by when they happened, inside its window.
     */
    static final class Watcher {
        private final List<Window> windows = new ArrayList<>();
        private volatile Instant flushed = Instant.EPOCH;

        Watcher(Fence fence) {
            RecordingStream stream = new RecordingStream();
            // Stack traces only on request: settings are shared by every recording in the JVM, so
            // traces here put one on each of the suite recording's file events too, and jk-engine's
            // grew from 17 MB to 44 MB with them, all of it parsed by the engine after the suite.
            boolean frames = FRAMES_ON.equals(System.getProperty(PROPERTY));
            EventSettings read = stream.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            EventSettings write = stream.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
            if (frames) {
                read.withStackTrace();
                write.withStackTrace();
            } else {
                read.withoutStackTrace();
                write.withoutStackTrace();
            }
            stream.enable("jdk.ProcessStart").withStackTrace();
            // By name: an unfiltered handler also gets every other event in the repository, those
            // the suite's own recording enables (jdk.SocketRead and jdk.SocketWrite have no path).
            for (String type : WATCHED) stream.onEvent(type, event -> take(fence, event));
            // Every event recorded before a flush is delivered before onFlush runs.
            stream.onFlush(() -> flushed = Instant.now());
            stream.setReuse(true);
            stream.setOrdered(false);
            stream.startAsync();
            Thread hook = new Thread(this::atExit, "hermetic-reads-exit");
            Runtime.getRuntime().addShutdownHook(hook);
        }

        /**
         * Judge one event. Nothing thrown here reaches the JFR thread, whose default is to print the
         * stack on the test JVM's stderr, where a test asserting on its own output reads it: a
         * failure is caught in the class's window as a guard error naming the event type.
         */
        void take(Fence fence, RecordedEvent event) {
            Instant at;
            try {
                at = event.getStartTime();
            } catch (RuntimeException e) {
                at = Instant.now();
            }
            String type = "?";
            try {
                type = event.getEventType().getName();
                Fields fields = fields(event);
                String what = judged(fence, type, fields);
                if (what == null || isHarness(event.getStackTrace()) || isHarnessGit(type, fields)) return;
                caught(at, what, frames(event.getStackTrace()));
            } catch (RuntimeException e) {
                caught(at, guardError(type, e), "");
            }
        }

        synchronized Window open(String testClass) {
            Window w = new Window(testClass, Instant.now());
            windows.add(w);
            // Only the unreported and the last few windows matter; the rest go.
            if (windows.size() > 64) windows.removeIf(x -> x.reported && x != w);
            return w;
        }

        /** The event at {@code at} goes to the window it happened in, else the last one started before it. */
        synchronized void caught(Instant at, String what, String frames) {
            Window into = null;
            for (Window w : windows) {
                if (!w.start.isAfter(at)) into = w;
            }
            if (into == null) return;
            into.caught.putIfAbsent(what, frames);
        }

        /**
         * Close {@code w} and hand back every finished window with offences not yet reported: its
         * own, and an earlier class's whose events JFR delivered after that class had finished.
         */
        synchronized List<Window> close(Window w) {
            w.end = Instant.now();
            List<Window> due = new ArrayList<>();
            for (Window x : windows) {
                if (x.end != null && !x.reported && !x.caught.isEmpty()) {
                    x.reported = true;
                    due.add(x);
                }
            }
            return due;
        }

        /**
         * At the JVM's exit, what its last classes did and JFR had not yet delivered: wait for one
         * flush past now (JFR flushes about every second), then report what is left on stderr and
         * end the JVM non-zero, so the run fails for it even though no class is left to fail.
         */
        private void atExit() {
            Instant now = Instant.now();
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (flushed.isBefore(now) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            List<Window> due;
            synchronized (this) {
                due = new ArrayList<>();
                for (Window x : windows) {
                    if (!x.reported && !x.caught.isEmpty()) {
                        x.reported = true;
                        due.add(x);
                    }
                }
            }
            if (due.isEmpty()) return;
            for (Window x : due) {
                keep(x.testClass, x.caught);
                System.err.println(message(x.testClass, x.caught));
            }
            System.err.flush();
            Runtime.getRuntime().halt(EXIT_REACHED);
        }
    }

    /** The exit status of a JVM whose last classes reached outside the sandbox after they finished. */
    static final int EXIT_REACHED = 71;

    /** The {@link #PROPERTY} value that records the frames reaching each path, at a recording's cost. */
    static final String FRAMES_ON = "frames";

    /** This JVM's watcher; null until the first class in a sandbox. */
    private static @Nullable Watcher watcher;

    /**
     * This JVM's watcher, made at its first class from that class's fence: before the stream starts,
     * so the fence's own look at the checkout's {@code .git} is not an event it judges.
     */
    private static synchronized @Nullable Watcher watcher(Class<?> anchor, String jkHome) {
        Watcher w = watcher;
        if (w == null) {
            Fence fence = fence(anchor, jkHome);
            if (fence == null) return null;
            w = new Watcher(fence);
            watcher = w;
        }
        return w;
    }

    /**
     * {@link CheckoutUntouched}'s own {@code git status} of the checkout, told by its arguments where
     * no stack trace is recorded.
     */
    static boolean isHarnessGit(String type, Fields event) {
        if (!type.equals("jdk.ProcessStart")) return false;
        String command = event.string("command");
        return command != null && command.contains(" --no-optional-locks status --porcelain=v1 -z ");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if ("off".equals(System.getProperty(PROPERTY))) return;
        String jkHome = System.getenv("JK_HOME");
        if (jkHome == null || realHome(jkHome) == null) return;
        sandboxMaven(Path.of(jkHome), System.getenv("JK_M2_LOCAL"));
        sandboxJGit(Path.of(jkHome));
        Watcher w = watcher(context.getRequiredTestClass(), jkHome);
        if (w == null) return;
        context.getStore(NS).put(WATCH, w.open(context.getRequiredTestClass().getName()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String keychain = keychain(context.getRequiredTestClass().getName(), KeychainSandbox.drainRefusals());
        Window window = context.getStore(NS).remove(WATCH, Window.class);
        Watcher w = watcher;
        if (window == null || w == null) {
            if (keychain != null) throw new AssertionError(keychain);
            return;
        }
        List<Window> due = w.close(window);
        if (due.isEmpty()) {
            if (keychain != null) throw new AssertionError(keychain);
            return;
        }
        StringBuilder failure = new StringBuilder();
        if (keychain != null) failure.append(keychain);
        for (Window x : due) {
            keep(x.testClass, x.caught);
            if (failure.length() > 0) failure.append("\n");
            failure.append(message(x.testClass, x.caught));
            if (x != window) {
                failure.append("\n  (")
                        .append(x.testClass)
                        .append(" had finished when JFR delivered these; ")
                        .append(context.getRequiredTestClass().getName())
                        .append(" is the class that fails for it)");
            }
        }
        throw new AssertionError(failure.toString());
    }

    /**
     * The failure for {@code testClass} having asked the real OS keychain, once per refusal {@link
     * KeychainSandbox} kept while it ran; null when it asked none.
     */
    static @Nullable String keychain(String testClass, List<String> refusals) {
        if (refusals.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(testClass)
                .append(" reached for the user's OS keychain, which no test may read, write or delete in;")
                .append(" a test JVM's logins go to its sandbox keychain (")
                .append(KeychainSandbox.ENV)
                .append("). Use SecretVault.current() or a fake SecretBackend, never a backend over the real")
                .append(" keychain:");
        for (String what : refusals.stream().distinct().limit(SHOWN).toList())
            sb.append("\n  ").append(what);
        return sb.toString();
    }

    /**
     * The Maven user configuration this JVM reads, moved into the sandbox unless a test already
     * named its own: the user repository is the sandbox's local repository ({@code JK_M2_LOCAL}),
     * and the user {@code settings.xml} a file under the sandbox home that does not exist. A
     * resolution in the suite then reads neither the developer's {@code ~/.m2/repository} nor their
     * mirrors, profiles and credentials, and passes or fails the same on a host with neither.
     */
    static void sandboxMaven(Path sandboxHome, @Nullable String sandboxM2) {
        if (sandboxM2 != null && !sandboxM2.isBlank() && System.getProperty(MAVEN_REPO_LOCAL) == null) {
            System.setProperty(MAVEN_REPO_LOCAL, sandboxM2);
        }
        if (System.getProperty(M2_SETTINGS) == null) {
            System.setProperty(
                    M2_SETTINGS, sandboxHome.resolve("m2-settings.xml").toString());
        }
    }

    /**
     * JGit in this JVM reads the sandbox's git configuration and no system file, as the {@code git}
     * the engine's test environment hands it does; through {@code cc.jumpkick.git.JGitEnvironment}
     * when the suite carries the engine, since this harness serves modules that do not.
     */
    static void sandboxJGit(Path sandboxHome) {
        if (System.getProperty(GitConfigFiles.GLOBAL_PROPERTY) == null) {
            System.setProperty(
                    GitConfigFiles.GLOBAL_PROPERTY,
                    sandboxHome.resolve("gitconfig").toString());
        }
        if (System.getProperty(GitConfigFiles.NOSYSTEM_PROPERTY) == null) {
            System.setProperty(GitConfigFiles.NOSYSTEM_PROPERTY, "1");
        }
        try {
            Class.forName("cc.jumpkick.git.JGitEnvironment")
                    .getMethod("install")
                    .invoke(null);
        } catch (ClassNotFoundException noEngine) {
            // No JGit in this suite.
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new AssertionError("cannot point JGit at the sandbox's git configuration", e);
        }
    }

    /** Maven's own name for the local repository, which jk reads as the user's repository. */
    static final String MAVEN_REPO_LOCAL = "maven.repo.local";

    /** jk's name for the user {@code settings.xml} ({@code MavenSettings.SETTINGS_PROPERTY}). */
    static final String M2_SETTINGS = "jk.m2.settings";

    /**
     * Every path {@code testClass} reached, with the frames that reached it, in {@code
     * hermetic-reads/<class>.txt} under the JVM's temp root: a failure shows the first few, and a
     * red run's sandbox is kept for a while to be read.
     */
    private static void keep(String testClass, Map<String, String> caught) {
        StringBuilder sb = new StringBuilder();
        caught.forEach((what, frames) ->
                sb.append(what).append("\n    at ").append(frames).append('\n'));
        try {
            Path dir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "hermetic-reads"));
            Files.writeString(dir.resolve(testClass + ".txt"), sb.toString());
        } catch (IOException | RuntimeException e) {
            // The failure still names the first paths.
        }
    }

    /** The failure for {@code testClass}, naming up to {@link #SHOWN} paths with the frames that reached each. */
    static String message(String testClass, Map<String, String> caught) {
        StringBuilder sb = new StringBuilder(testClass)
                .append(" reached outside its sandbox: the real jk home, the checkout's .git, git's own")
                .append(
                        " configuration or the OS keychain. Give the test a temp home, a temp repository and the sandbox's")
                .append(
                        " git configuration (jk's test JVMs carry all three); see cc.jumpkick.config.testing.HermeticReads; jk test --jvm-arg=-Djk.test.hermetic=frames shows the code that reached each path.");
        int shown = 0;
        for (Map.Entry<String, String> e : caught.entrySet()) {
            if (shown++ == SHOWN) {
                sb.append("\n  (+").append(caught.size() - SHOWN).append(" more)");
                break;
            }
            sb.append("\n  ").append(e.getKey());
            if (!e.getValue().isEmpty()) sb.append("\n      at ").append(e.getValue());
        }
        return sb.toString();
    }

    /** The event types the guard subscribes to and judges; any other is none of its business. */
    static final List<String> WATCHED = List.of("jdk.FileRead", "jdk.FileWrite", "jdk.ProcessStart");

    /** An event's string fields, absent ones as null: what {@link #offence} reads of a recorded event. */
    @FunctionalInterface
    interface Fields {
        @Nullable
        String string(String name);
    }

    /** {@code event}'s fields, a field this JDK's event type does not have read as absent. */
    static Fields fields(RecordedEvent event) {
        return name -> event.hasField(name) ? event.getString(name) : null;
    }

    /** {@link #offence}, a failure reading the event turned into a {@link #guardError} rather than thrown. */
    static @Nullable String judged(Fence fence, String type, Fields event) {
        try {
            return offence(fence, type, event);
        } catch (RuntimeException e) {
            return guardError(type, e);
        }
    }

    /** What a guard failure on one event reads as in the class's report: the type and the error. */
    static String guardError(String type, RuntimeException e) {
        return "guard error on a " + type + " event (the class was not judged on it): " + e;
    }

    /** What {@code event} touched that {@code fence} denies, as shown in the failure; null when nothing. */
    static @Nullable String offence(Fence fence, String type, Fields event) {
        if (!WATCHED.contains(type)) return null;
        if (type.equals("jdk.ProcessStart")) {
            String command = event.string("command");
            if (command == null) return null;
            if (isKeychainTool(command)) return "ran " + command + " (the OS keychain)";
            return fence.deniesGit(event.string("directory"), command) ? "ran " + command : null;
        }
        String raw = event.string("path");
        if (raw == null || raw.isBlank()) return null;
        Path p = path(raw);
        if (p == null || !fence.denies(p)) return null;
        boolean write = type.equals("jdk.FileWrite");
        if (!fence.deniesRead(p)) {
            // A read there is the known gap; so is the access stamp the store keeps beside it.
            if (!write || isAccessLedger(p)) return null;
        }
        return (write ? "wrote " : "read ") + p;
    }

    /**
     * The fence for a suite whose classes load from under a checkout and whose {@code JK_HOME} is
     * {@code jkHome}; null outside a jk test sandbox.
     */
    static @Nullable Fence fence(Class<?> anchor, @Nullable String jkHome) {
        Path realHome = realHome(jkHome);
        if (realHome == null) return null;
        List<Path> denied = new ArrayList<>();
        List<Path> allowed = new ArrayList<>();
        addBoth(denied, realHome);
        addBoth(allowed, realHome.resolve("test-homes"));
        addBoth(allowed, realHome.resolve("test-store"));
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path home = Path.of(userHome);
            addBoth(denied, home.resolve(".jk"));
            addBoth(denied, home.resolve(".m2").resolve("repository"));
            addBoth(denied, home.resolve(".m2").resolve("settings.xml"));
            addBoth(denied, home.resolve(".gitconfig"));
            addBoth(denied, home.resolve(".config").resolve("git"));
        }
        addBoth(denied, Path.of("/etc/gitconfig"));
        Path checkout = checkout(anchor);
        if (checkout != null) {
            for (Path gitDir : gitDirs(checkout)) addBoth(denied, gitDir);
        }
        // The JDK and the suite's own classpath (jars in ~/.m2 among them) are keyed by the build:
        // reading them is the run.
        addBoth(allowed, Path.of(System.getProperty("java.home")));
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            Path p = entry.isBlank() ? null : path(entry);
            if (p != null) addBoth(allowed, p);
        }
        // TODO(JK-S589D): stop reading these. Read (and stamped in its access ledger) on purpose: a
        // workspace-built worker's POM closure is read from the developer's real store and Maven-local tree
        // (PomRuntimeClasspath.unsandboxedProductStore), on a resolver thread no frame ties to it.
        List<Path> readable = new ArrayList<>();
        addBoth(readable, realHome.resolve("store"));
        if (userHome != null && !userHome.isBlank()) {
            addBoth(readable, Path.of(userHome).resolve(".jk").resolve("store"));
            addBoth(readable, Path.of(userHome).resolve(".m2").resolve("repository"));
        }
        // Engines a test starts live in its own sandbox slot or under its temp roots.
        List<Path> own = new ArrayList<>();
        Path sandbox = jkHome == null ? null : path(jkHome);
        Path slot = sandbox == null ? null : sandbox.getParent();
        if (slot != null) addBoth(own, slot);
        addBoth(own, Path.of(System.getProperty("java.io.tmpdir")));
        if (userHome != null && !userHome.isBlank())
            addBoth(own, Path.of(userHome).resolve(".jk-test-tmp"));
        return new Fence(List.copyOf(denied), List.copyOf(allowed), List.copyOf(readable), List.copyOf(own), checkout);
    }

    /**
     * The developer's home a sandboxed {@code JK_HOME} was made under: the directory holding its
     * {@code test-homes}, the outermost one, since a suite's own fixtures nest sandboxes inside its
     * sandbox. Null when {@code jkHome} is no test sandbox.
     */
    static @Nullable Path realHome(@Nullable String jkHome) {
        if (jkHome == null || jkHome.isBlank()) return null;
        Path home = path(jkHome);
        if (home == null) return null;
        Path root = home.getRoot();
        Path at = root;
        for (Path name : home) {
            if (name.toString().equals("test-homes")) return at;
            at = at == null ? name : at.resolve(name);
        }
        return null;
    }

    /** The root of the jk checkout {@code anchor} was loaded from; null when it was not. */
    static @Nullable Path checkout(Class<?> anchor) {
        try {
            return RepoRoot.find(anchor);
        } catch (AssertionError notInACheckout) {
            return null;
        }
    }

    /**
     * The git directories of the work tree at {@code checkout}: {@code .git} itself, or for a linked
     * worktree the directory its {@code .git} file names and that directory's common one.
     */
    static List<Path> gitDirs(Path checkout) {
        Path dotGit = checkout.resolve(".git");
        if (Files.isDirectory(dotGit)) return List.of(dotGit);
        if (!Files.isRegularFile(dotGit)) return List.of();
        List<Path> out = new ArrayList<>();
        try {
            String line = Files.readString(dotGit).strip();
            if (!line.startsWith("gitdir:")) return List.of();
            Path gitDir =
                    checkout.resolve(line.substring("gitdir:".length()).strip()).normalize();
            out.add(gitDir);
            Path common = gitDir.resolve("commondir");
            if (Files.isRegularFile(common)) {
                out.add(gitDir.resolve(Files.readString(common).strip()).normalize());
            }
        } catch (IOException | InvalidPathException unreadable) {
            return out;
        }
        return out;
    }

    /**
     * Whether {@code command} asks the user's keychain: libsecret's {@code secret-tool}, macOS's
     * {@code security}, or the Secret Service's bus name through {@code busctl} or {@code gdbus}.
     */
    static boolean isKeychainTool(String command) {
        Matcher m = PROGRAM.matcher(command.strip());
        if (!m.find()) return false;
        String program = m.group(1).toLowerCase(Locale.ROOT);
        return switch (program) {
            case "secret-tool", "security" -> true;
            case "busctl", "gdbus" -> command.contains("org.freedesktop.secrets");
            default -> false;
        };
    }

    /** A command's program, its last path segment without {@code .exe}. */
    private static final Pattern PROGRAM = Pattern.compile("^(?:\\S*[\\\\/])?([^\\s\\\\/]+?)(?:\\.exe)?(?:\\s|$)");

    /** Whether {@code command}'s program is git. */
    static boolean isGit(String command) {
        return GIT.matcher(command.strip()).matches();
    }

    /** A command line whose program is git: a bare {@code git}, or a path to it, spaces and all. */
    private static final Pattern GIT = Pattern.compile("(?i)^(.*?[\\\\/])?git(\\.exe)?(\\s.*)?$");

    /** The directory a git command works on: its {@code -C} argument against {@code dir}, else {@code dir}. */
    static @Nullable Path gitTarget(String command, @Nullable Path dir) {
        String[] words = command.strip().split("\\s+");
        Path target = dir;
        for (int i = 1; i < words.length - 1; i++) {
            if (!words[i].equals("-C")) continue;
            Path c = path(words[i + 1]);
            if (c == null) continue;
            target = c.isAbsolute() || target == null ? c : target.resolve(c).normalize();
        }
        return target;
    }

    /** Whether {@code p} lies under a {@code state/engine} directory: an engine's endpoint, pid, socket or log. */
    static boolean isEngineState(Path p) {
        String prev = "";
        for (Path name : p) {
            String n = name.toString();
            if (prev.equals("state") && n.equals("engine")) return true;
            prev = n;
        }
        return false;
    }

    /** Whether {@code p} is a store tree's access ledger ({@code <tree>/.access/...}). */
    static boolean isAccessLedger(Path p) {
        for (Path name : p) {
            if (name.toString().equals(".access")) return true;
        }
        return false;
    }

    /** Whether a frame of {@code trace} is the harness's own look at the checkout. */
    static boolean isHarness(@Nullable RecordedStackTrace trace) {
        if (trace == null) return false;
        for (RecordedFrame f : trace.getFrames()) {
            if (!f.isJavaFrame()) continue;
            String type = f.getMethod().getType().getName();
            if (type.equals(CheckoutUntouched.class.getName()) || type.equals(HermeticReads.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first {@link #FRAMES} frames of jk's own code in {@code trace}, innermost first, then the
     * outermost one recorded (the test method, when the stack was shallow enough to keep it).
     */
    static String frames(@Nullable RecordedStackTrace trace) {
        if (trace == null) return "";
        List<String> jk = new ArrayList<>();
        for (RecordedFrame f : trace.getFrames()) {
            if (!f.isJavaFrame()) continue;
            String type = f.getMethod().getType().getName();
            if (!type.startsWith("cc.jumpkick.")) continue;
            String simple = type.substring(type.lastIndexOf('.') + 1);
            jk.add(simple + "." + f.getMethod().getName() + ":" + f.getLineNumber());
        }
        StringJoiner out = new StringJoiner(" <- ");
        for (int i = 0; i < Math.min(FRAMES, jk.size()); i++) out.add(jk.get(i));
        if (jk.size() > FRAMES) out.add("... " + jk.getLast());
        return out.toString();
    }

    private static void addBoth(List<Path> into, Path p) {
        Path abs = p.toAbsolutePath().normalize();
        into.add(abs);
        real(abs).filter(r -> !r.equals(abs)).ifPresent(into::add);
    }

    private static boolean under(Path p, List<Path> roots) {
        for (Path r : roots) {
            if (p.startsWith(r)) return true;
        }
        return false;
    }

    private static Optional<Path> real(Path p) {
        try {
            return Optional.of(p.toRealPath());
        } catch (IOException | RuntimeException absent) {
            return Optional.empty();
        }
    }

    private static @Nullable Path path(String raw) {
        try {
            return Objects.requireNonNull(Path.of(raw.strip())).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
    }
}
