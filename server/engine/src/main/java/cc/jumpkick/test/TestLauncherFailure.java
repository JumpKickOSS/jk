// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Classpaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The forked test runner ended before it ran a test: no JUnit event reached the parent and the
 * process exited non-zero. That is a launcher failure, not a failed test — an engine that could
 * not start, a launcher missing from the classpath, a JVM that would not boot — so it fails the
 * {@code run-tests} step instead of counting as one red test. What the fork printed is the whole
 * evidence and rides along, with what can be read off it: the exception the runner reported, the
 * engine JUnit named, the cause chain, the JVM's own refusal to start. The message always carries
 * the exit — with the signal's name when the exit is {@code 128 + signal} — and the fork's last
 * words: the line the output classifies as, else its last {@link #LAST_LINES} lines, else that it
 * printed nothing. The command the fork was started with rides along too, for the fork that said
 * nothing: the java binary, the JVM flags and the arguments are then the whole diagnostic.
 */
public final class TestLauncherFailure extends RuntimeException {

    /** The runner's own header: {@code jk-test-runner: [test discovery failed: ]<class>: <message>}. */
    private static final String RUNNER_PREFIX = "jk-test-runner: ";

    private static final String DISCOVERY_PREFIX = "test discovery failed: ";
    private static final String CAUSE_PREFIX = "caused by: ";

    /** The runner's line naming a class discovery could not load. */
    private static final String CLASS_PREFIX = "class: ";

    /** Frames kept under the first cause: enough to name where, not the whole trace. */
    static final int FRAMES = 3;

    /** Java's own {@code printStackTrace} cause line. */
    private static final String JAVA_CAUSE_PREFIX = "Caused by: ";

    /** HotSpot's last word under {@code -XX:+ExitOnOutOfMemoryError}, before it exits 3. */
    private static final String JVM_OOM_PREFIX = "Terminating due to ";

    /** Lines kept from the end of an output nothing else classifies. */
    static final int LAST_LINES = 5;

    /** HotSpot's first line when it could not reserve or commit what a flag asked; the reason follows it. */
    private static final String JVM_INIT_FAILED = "Error occurred during initialization of VM";

    /** The launcher's line under a flag it refused; a specific line precedes it and is preferred. */
    private static final String JVM_COULD_NOT_CREATE = "Error: Could not create the Java Virtual Machine.";

    /** The hs_err report's opener; the signal line follows it. */
    private static final String JVM_FATAL_ERROR = "A fatal error has been detected by the Java Runtime Environment";

    /** Lines the JVM launcher or HotSpot print, on their own, when a flag or the machine refused the start. */
    private static final List<String> JVM_REFUSAL_PREFIXES = List.of(
            "Invalid initial heap size",
            "Invalid maximum heap size",
            "Invalid thread stack size",
            "Improperly specified VM option",
            "Unrecognized VM option",
            "Unrecognized option",
            "Error: Could not find or load main class",
            "Error: Unable to initialize main class",
            "Error: Unable to access jarfile",
            "There is insufficient memory for the Java Runtime Environment to continue",
            "Native memory allocation");

    /** Signal names by number, for an exit of {@code 128 + signal}; the rest read {@code signal N}. */
    private static final String[] SIGNALS = {
        "", "SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGABRT", "SIGBUS", "SIGFPE", "SIGKILL", "SIGUSR1",
        "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM"
    };

    private static final Pattern ENGINE_ID = Pattern.compile("TestEngine with ID '([^']+)'");
    private static final Pattern CLASS_NAME = Pattern.compile("^([A-Za-z_$][\\w$]*\\.)+[A-Z][\\w$]*$");

    /** Flags whose next argument is a path list, folded to its entry count in {@link #commandLine}. */
    private static final Set<String> PATH_LIST_FLAGS =
            Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path");

    private final String moduleLabel;
    private final String phase;
    private final int exit;
    private final String output;
    private final List<String> command;

    private TestLauncherFailure(String moduleLabel, String phase, int exit, String output, List<String> command) {
        super(phase + " exited " + exit + signalSuffix(exit) + " before any test ran" + headlineSuffix(output));
        this.moduleLabel = moduleLabel == null ? "" : moduleLabel;
        this.phase = phase;
        this.exit = exit;
        this.output = output == null ? "" : output;
        this.command = List.copyOf(command);
    }

    /** The list-only discovery fork died with nothing named; the command it was started with is not at hand. */
    public static TestLauncherFailure discovery(String moduleLabel, int exit, String output) {
        return discovery(moduleLabel, exit, output, List.of());
    }

    /** The list-only discovery fork, started as {@code command}, died with nothing named. */
    public static TestLauncherFailure discovery(String moduleLabel, int exit, String output, List<String> command) {
        return new TestLauncherFailure(moduleLabel, "test discovery", exit, output, command);
    }

    /** A suite-running fork died before its first event; the command it was started with is not at hand. */
    public static TestLauncherFailure runner(String moduleLabel, int exit, String output) {
        return runner(moduleLabel, exit, output, List.of());
    }

    /** A suite-running fork (the single runner or a pool worker), started as {@code command}, died before its first event. */
    public static TestLauncherFailure runner(String moduleLabel, int exit, String output, List<String> command) {
        return new TestLauncherFailure(moduleLabel, "test runner", exit, output, command);
    }

    public String moduleLabel() {
        return moduleLabel;
    }

    /** {@code test discovery} or {@code test runner}. */
    public String phase() {
        return phase;
    }

    public int exit() {
        return exit;
    }

    /** Everything the fork printed outside the protocol, newest {@code CaptureBuffer} tail. */
    public String output() {
        return output;
    }

    /** The argv the fork was started with — java binary first — or empty when the launcher did not record it. */
    public List<String> command() {
        return command;
    }

    /**
     * {@link #command} as one line: arguments with whitespace single-quoted, and a class path or
     * module path folded to {@code <N entries>}, since a hundred jar paths hide the flags a reader
     * needs. Empty when the command was not recorded.
     */
    public String commandLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (i > 0) sb.append(' ');
            sb.append(quoted(arg));
            if (PATH_LIST_FLAGS.contains(arg) && i + 1 < command.size()) {
                String list = command.get(++i);
                int entries = list.isEmpty() ? 0 : list.split(Pattern.quote(Classpaths.SEPARATOR), -1).length;
                sb.append(" <").append(entries).append(entries == 1 ? " entry>" : " entries>");
            }
        }
        return sb.toString();
    }

    private static String quoted(String arg) {
        boolean plain = !arg.isEmpty() && arg.chars().noneMatch(Character::isWhitespace);
        return plain ? arg : "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * The exception class the fork named, or {@code ""} when its output names none: the runner's
     * own header when it printed one, else the JVM's out-of-memory exit, else the first exception
     * line of a stack trace some framework printed.
     */
    public String exceptionClass() {
        String header = header(output);
        if (header == null) return "";
        int colon = header.indexOf(": ");
        String cls = colon < 0 ? header : header.substring(0, colon);
        return CLASS_NAME.matcher(cls).matches() ? cls : "";
    }

    /** The exception message the fork named, or {@code ""}. */
    public String headline() {
        String header = header(output);
        if (header == null) return "";
        int colon = header.indexOf(": ");
        if (colon < 0 || !CLASS_NAME.matcher(header.substring(0, colon)).matches()) return header;
        return header.substring(colon + 2).strip();
    }

    /** True when the fork was the JVM itself running out of memory, not a runner exception. */
    public boolean outOfMemory() {
        return "java.lang.OutOfMemoryError".equals(exceptionClass());
    }

    /**
     * True when the JVM refused to start at all — a heap it could not reserve, a flag it did not
     * accept, native memory it could not map — so no class of the suite was ever loaded.
     */
    public boolean jvmRefused() {
        return jvmRefusal(output) != null;
    }

    /**
     * The signal that ended the fork when the exit is {@code 128 + signal} ({@code SIGKILL} for
     * 137), or {@code null} for an exit the fork chose itself.
     */
    public @Nullable String signal() {
        return signalName(exit);
    }

    /** The last {@link #LAST_LINES} non-blank lines the fork printed, oldest first; empty when it printed nothing. */
    public List<String> lastLines() {
        List<String> kept = new ArrayList<>();
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0 && kept.size() < LAST_LINES; i--) {
            String t = lines[i].strip();
            if (!t.isEmpty()) kept.add(t);
        }
        return List.copyOf(kept.reversed());
    }

    /**
     * The innermost {@code caused by} the fork printed, {@code <class>: <message>}, or the header
     * itself when there is no chain; {@code ""} when the output names nothing.
     */
    public String rootCause() {
        List<String> causes = causes();
        if (!causes.isEmpty()) return causes.getLast();
        String header = header(output);
        return header == null ? "" : header;
    }

    /**
     * The classes the runner's discovery could not load, from its {@code class:} lines, in the
     * order printed; empty when the fork named none.
     */
    public List<String> droppedClasses() {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith(CLASS_PREFIX))
                out.add(t.substring(CLASS_PREFIX.length()).strip());
        }
        return List.copyOf(out);
    }

    /**
     * The first frames under the first cause the fork printed — the {@code at …} lines that follow
     * it, without the prefix, at most {@link #FRAMES} — or empty when no cause has frames.
     */
    public List<String> frames() {
        List<String> out = new ArrayList<>();
        boolean underCause = false;
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith(CAUSE_PREFIX) || t.startsWith(JAVA_CAUSE_PREFIX)) {
                if (!out.isEmpty()) break;
                underCause = true;
            } else if (underCause && t.startsWith("at ")) {
                if (out.size() < FRAMES) out.add(t.substring("at ".length()).strip());
            } else if (underCause) {
                // The frames of a cause follow it directly; anything else ends them.
                underCause = false;
                if (!out.isEmpty()) break;
            }
        }
        return List.copyOf(out);
    }

    /** The JUnit Platform engine id in {@code TestEngine with ID '…'}, or {@code null}. */
    public @Nullable String engineId() {
        Matcher m = ENGINE_ID.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The cause chain the fork printed, outermost first: the runner's own {@code caused by:} lines
     * when it printed any, else the {@code Caused by:} lines of the first stack trace in the output
     * — a framework's bootstrap failure the JVM never let the runner report.
     */
    public List<String> causes() {
        List<String> runner = new ArrayList<>();
        List<String> java = new ArrayList<>();
        boolean firstTrace = true;
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith(CAUSE_PREFIX)) {
                runner.add(t.substring(CAUSE_PREFIX.length()).strip());
            } else if (t.startsWith(JAVA_CAUSE_PREFIX) && firstTrace) {
                java.add(t.substring(JAVA_CAUSE_PREFIX.length()).strip());
            } else if (!java.isEmpty() && exceptionLine(t) != null) {
                firstTrace = false;
            }
        }
        return List.copyOf(runner.isEmpty() ? java : runner);
    }

    /**
     * The first line that names an exception, without its prefixes, or {@code null} when the fork
     * printed none: the runner's {@code jk-test-runner:} header, else the JVM's {@code Terminating
     * due to java.lang.OutOfMemoryError: …}, else the {@code <class>: <message>} line that opens a
     * stack trace.
     */
    private static @Nullable String header(@Nullable String output) {
        if (output == null) return null;
        String jvm = null;
        String trace = null;
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith(RUNNER_PREFIX)) {
                String rest = t.substring(RUNNER_PREFIX.length());
                if (rest.startsWith(DISCOVERY_PREFIX)) rest = rest.substring(DISCOVERY_PREFIX.length());
                return rest.strip();
            }
            if (jvm == null && t.startsWith(JVM_OOM_PREFIX))
                jvm = t.substring(JVM_OOM_PREFIX.length()).strip();
            if (trace == null) trace = exceptionLine(t);
        }
        if (jvm != null) return jvm;
        String refusal = jvmRefusal(output);
        return refusal != null ? refusal : trace;
    }

    /**
     * The line that says why the JVM would not start, or {@code null}: the reason under HotSpot's
     * {@code Error occurred during initialization of VM}, the launcher's own line for a flag it
     * refused, the hs_err report's memory or signal line — the launcher's generic {@code Could not
     * create the Java Virtual Machine} only when no line before it is more specific.
     */
    private static @Nullable String jvmRefusal(@Nullable String output) {
        if (output == null) return null;
        String[] lines = output.split("\n");
        boolean generic = false;
        for (int i = 0; i < lines.length; i++) {
            String t = stripReportMarker(lines[i]);
            if (t.startsWith(JVM_INIT_FAILED) || t.startsWith(JVM_FATAL_ERROR)) {
                String next = nextReportLine(lines, i + 1);
                return next != null ? next : t;
            }
            for (String prefix : JVM_REFUSAL_PREFIXES) {
                if (t.startsWith(prefix)) return t;
            }
            if (t.startsWith(JVM_COULD_NOT_CREATE)) generic = true;
        }
        return generic ? JVM_COULD_NOT_CREATE : null;
    }

    /** The next non-blank line, its {@code #} report marker removed, or {@code null} past the end. */
    private static @Nullable String nextReportLine(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            String t = stripReportMarker(lines[i]);
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    /** The line without the {@code #} an hs_err report prefixes, stripped of surrounding blanks. */
    private static String stripReportMarker(String line) {
        String t = line.strip();
        return t.startsWith("#") ? t.substring(1).strip() : t;
    }

    /** {@code " (SIGKILL)"} for an exit of {@code 128 + signal}; {@code ""} for the fork's own exit. */
    private static String signalSuffix(int exit) {
        String signal = signalName(exit);
        return signal == null ? "" : " (" + signal + ")";
    }

    private static @Nullable String signalName(int exit) {
        if (exit <= 128 || exit > 128 + 64) return null;
        int number = exit - 128;
        return number < SIGNALS.length ? SIGNALS[number] : "signal " + number;
    }

    /** {@code t} when it is a bare {@code <class>: <message>} or {@code <class>} exception line. */
    private static @Nullable String exceptionLine(String t) {
        if (t.isEmpty() || t.startsWith("at ") || t.startsWith("...")) return null;
        int colon = t.indexOf(": ");
        String cls = colon < 0 ? t : t.substring(0, colon);
        return CLASS_NAME.matcher(cls).matches() ? t : null;
    }

    /**
     * What follows the exit in the message: the runner's own headline when it reported one, else
     * the whole {@code <class>: <message>} the JVM or a framework printed, since a bare
     * {@code Metaspace} says nothing without its {@code OutOfMemoryError}; when no line
     * classifies, the fork's last lines, or that it printed nothing.
     */
    private static String headlineSuffix(@Nullable String output) {
        String header = header(output);
        if (header == null || header.isBlank()) return lastWordsSuffix(output);
        if (!fromRunner(output)) return " — " + header;
        int colon = header.indexOf(": ");
        String headline =
                colon >= 0 && CLASS_NAME.matcher(header.substring(0, colon)).matches()
                        ? header.substring(colon + 2).strip()
                        : header;
        return headline.isBlank() ? "" : " — " + headline;
    }

    /** The fork's last lines, one per indented line, or the silence named. */
    private static String lastWordsSuffix(@Nullable String output) {
        if (output == null || output.isBlank()) return " — the fork printed nothing";
        StringBuilder sb = new StringBuilder(" — the fork's last output:");
        List<String> kept = new ArrayList<>();
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0 && kept.size() < LAST_LINES; i--) {
            String t = lines[i].strip();
            if (!t.isEmpty()) kept.add(t);
        }
        for (String line : kept.reversed()) sb.append("\n    ").append(line);
        return sb.toString();
    }

    private static boolean fromRunner(@Nullable String output) {
        if (output == null) return false;
        for (String line : output.split("\n")) {
            if (line.strip().startsWith(RUNNER_PREFIX)) return true;
        }
        return false;
    }
}
