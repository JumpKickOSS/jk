// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.process.CommandChannel;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * {@code jk test} child JVM: argument parsing and the three run modes — one-shot, list-only
 * discovery, and pull worker ({@code RUN}/{@code DONE}). Exit 0/1/2; protocol on stdout per
 * {@link EventType}.
 *
 * <p>Every mode runs through {@link LauncherPath}; this class owns no discovery, execution,
 * filtering or event-shaping logic of its own.
 */
public final class TestRunner implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-test-runner", "##JKT:");
    }

    @Override
    public int run(List<String> argList, ProtocolWriter out) {
        Args parsed;
        try {
            parsed = Args.parse(argList.toArray(new String[0]));
        } catch (IllegalArgumentException e) {
            System.err.println("jk-test-runner: " + e.getMessage());
            System.err.println("usage: jk-test-runner --scan-classpath=<dir> "
                    + "[--list-only] [--pull --worker=<id> --commands=<host>:<port> --token=<hex>] [--filter=<regex>] "
                    + "[--method=<class regex>#<method>]... [--include-tags=a,b] [--exclude-tags=c,d]");
            return Exit.USAGE;
        }

        if (!LauncherPath.available()) {
            // Not a fallback situation: `jk lock` injects junit-platform-launcher into every
            // project's test classpath, so its absence means a hand-edited lockfile or a
            // classpath jk did not build. Say that instead of quietly running a second engine
            // driver whose tag and event semantics differ from the Launcher's.
            System.err.println(
                    "jk-test-runner: the JUnit Platform Launcher is not on the test classpath;" + " run jk lock");
            // The user's declared test dependencies are wrong — the one thing 2 still means.
            return Exit.CONFIG;
        }

        try (var writer = new JsonEventWriter(out)) {
            if (parsed.listOnly) {
                return runListOnly(parsed, writer);
            } else if (parsed.pull) {
                return runPullMode(parsed, writer);
            } else {
                return runOneShot(parsed, writer);
            }
        } catch (LinkageError e) {
            String where = String.valueOf(e.getMessage());
            if (where.contains("junit/platform") || where.contains("junit.platform")) {
                System.err.println("jk-test-runner: incompatible JUnit Platform on the test classpath: "
                        + e.getClass().getSimpleName()
                        + ": "
                        + e.getMessage());
                System.err.println("  add junit-jupiter, or spring-boot-starter-test in a Spring Boot app");
                return Exit.CONFIG;
            }
            String header = "jk-test-runner: " + e.getClass().getName() + ": " + e.getMessage();
            System.err.println(header);
            e.printStackTrace(System.err);
            System.err.println(header);
            return Exit.SOFTWARE;
        } catch (Throwable t) {
            String header = "jk-test-runner: " + t.getClass().getName() + ": " + t.getMessage();
            System.err.println(header);
            if (String.valueOf(t.getMessage()).contains("without at least one TestEngine")) {
                System.err.println("  no test engine; add junit-jupiter or junit:junit to [test-dependencies]");
            }
            t.printStackTrace(System.err);
            // The engine keeps a bounded head and tail of this stream: the header goes out again
            // after the trace so the last lines name the failure however long the trace ran.
            System.err.println(header);
            return Exit.SOFTWARE;
        }
    }

    private static int runOneShot(Args args, EventWriter writer) {
        return LauncherPath.runOneShot(
                args.scanClasspath,
                args.filter,
                args.methods,
                args.includeTags,
                args.excludeTags,
                args.workerId,
                writer);
    }

    private static int runListOnly(Args args, EventWriter writer) {
        return LauncherPath.runListOnly(
                args.scanClasspath,
                args.filter,
                args.methods,
                args.includeTags,
                args.excludeTags,
                args.workerId,
                writer);
    }

    /**
     * Pull mode reads its commands from the parent's loopback socket, not stdin: the parent closes
     * this JVM's stdin at start, so a test that reads {@code System.in}, or a child it gives this
     * JVM's stdin to, gets end-of-input instead of the command stream.
     */
    private static int runPullMode(Args args, EventWriter writer) throws Exception {
        try (BufferedReader in =
                CommandChannel.connect(Objects.requireNonNull(args.commands), Objects.requireNonNull(args.token))) {
            return runPullMode(args, writer, in);
        }
    }

    /**
     * Pull worker: {@code ready} → {@code RUN <class>} … → {@code DONE}. The exit code says whether
     * the session ran to completion, nothing else: test outcomes travel as events, and the driver
     * treats any non-zero exit as a worker that died with work still owed (it fails the suite as
     * "exited N mid-run"). Exiting 1 because some class failed is therefore wrong twice over — the
     * failure is already counted, and the driver adds a phantom one. Only an input that ends
     * without {@code DONE} (the driver vanished) exits non-zero. The driver also fails a worker
     * that ends before it was told {@code DONE}, whatever its exit: a test that calls {@code
     * System.exit(0)} mid-class owes that class and every class still queued.
     */
    static int runPullMode(Args args, EventWriter writer, BufferedReader commands) throws Exception {
        LauncherPath.emitReady(writer, args.workerId);
        Launcher launcher = LauncherFactory.create();
        String line;
        while ((line = commands.readLine()) != null) {
            if (line.equals("DONE")) return 0;
            if (!line.startsWith("RUN ")) {
                System.err.println("jk-test-runner: ignoring unknown command: " + line);
                continue;
            }
            String className = line.substring(4).trim();
            LauncherPath.runClass(
                    launcher, className, args.methods, args.includeTags, args.excludeTags, args.workerId, writer);
            LauncherPath.emitReady(writer, args.workerId);
        }
        System.err.println("jk-test-runner: the command channel closed before DONE");
        return 1;
    }

    /**
     * The {@code --filter} contract: substring match for plain patterns, verbatim when the caller
     * anchored it. Pre-fix the launcher wrapped everything in {@code .*….*}, which broke anchored
     * regexes.
     */
    static String classNamePattern(String filter) {
        String f = filter.trim();
        if (f.startsWith("^") || f.endsWith("$")) return f;
        return ".*" + f + ".*";
    }

    /** {@code methods} are the parsed {@code --method=} selections; empty when {@code --class} named whole classes. */
    record Args(
            Path scanClasspath,
            @Nullable String filter,
            List<MethodSelection> methods,
            boolean listOnly,
            boolean pull,
            int workerId,
            List<String> includeTags,
            List<String> excludeTags,
            @Nullable String commands,
            @Nullable String token) {

        static Args parse(String[] argv) {
            Path scan = null;
            String filter = null;
            List<String> methods = new ArrayList<>();
            boolean listOnly = false;
            boolean pull = false;
            int workerId = 0;
            List<String> includeTags = new ArrayList<>();
            List<String> excludeTags = new ArrayList<>();
            String commands = null;
            String token = null;
            for (var a : argv) {
                if (a.startsWith("--scan-classpath=")) {
                    scan = Path.of(a.substring("--scan-classpath=".length()));
                } else if (a.startsWith("--filter=")) {
                    filter = a.substring("--filter=".length());
                } else if (a.startsWith("--method=")) {
                    methods.add(a.substring("--method=".length()));
                } else if (a.equals("--list-only")) {
                    listOnly = true;
                } else if (a.equals("--pull")) {
                    pull = true;
                } else if (a.startsWith(CommandChannel.COMMANDS)) {
                    commands = a.substring(CommandChannel.COMMANDS.length());
                } else if (a.startsWith(CommandChannel.TOKEN)) {
                    token = a.substring(CommandChannel.TOKEN.length());
                } else if (a.startsWith("--worker=")) {
                    workerId = Integer.parseInt(a.substring("--worker=".length()));
                } else if (a.startsWith("--include-tags=")) {
                    splitCsv(a.substring("--include-tags=".length()), includeTags);
                } else if (a.startsWith("--exclude-tags=")) {
                    splitCsv(a.substring("--exclude-tags=".length()), excludeTags);
                } else if (a.equals("--fail-fast")) {
                    // accepted but currently a no-op — wired in a follow-up
                } else {
                    // A stale installed jk-test-runner driven by a newer engine lands here —
                    // name the likely cause instead of a bare unknown-arg.
                    throw new IllegalArgumentException(
                            "unknown arg: " + a + "; reinstall jk so the test runner matches the engine");
                }
            }
            if (scan == null) {
                throw new IllegalArgumentException("--scan-classpath=<dir> is required");
            }
            if (listOnly && pull) {
                throw new IllegalArgumentException("--list-only and --pull are mutually exclusive");
            }
            if (pull && (commands == null || token == null)) {
                throw new IllegalArgumentException("--pull needs --commands=<host>:<port> and --token=<hex>");
            }
            return new Args(
                    scan,
                    filter,
                    MethodSelection.parse(methods),
                    listOnly,
                    pull,
                    workerId,
                    List.copyOf(includeTags),
                    List.copyOf(excludeTags),
                    commands,
                    token);
        }

        private static void splitCsv(String csv, List<String> out) {
            if (csv == null || csv.isBlank()) return;
            for (String p : csv.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
    }
}
