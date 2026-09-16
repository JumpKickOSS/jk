// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The forked test runner ended before it ran a test: no JUnit event reached the parent and the
 * process exited non-zero. That is a launcher failure, not a failed test — an engine that could
 * not start, a launcher missing from the classpath, a JVM that would not boot — so it fails the
 * {@code run-tests} step instead of counting as one red test. What the fork printed is the whole
 * evidence and rides along, with what can be read off it: the exception the runner reported, the
 * engine JUnit named, the cause chain.
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

    private static final Pattern ENGINE_ID = Pattern.compile("TestEngine with ID '([^']+)'");
    private static final Pattern CLASS_NAME = Pattern.compile("^([A-Za-z_$][\\w$]*\\.)+[A-Z][\\w$]*$");

    private final String moduleLabel;
    private final String phase;
    private final int exit;
    private final String output;

    private TestLauncherFailure(String moduleLabel, String phase, int exit, String output) {
        super(phase + " exited " + exit + " before any test ran" + headlineSuffix(output));
        this.moduleLabel = moduleLabel == null ? "" : moduleLabel;
        this.phase = phase;
        this.exit = exit;
        this.output = output == null ? "" : output;
    }

    /** The list-only discovery fork died with nothing named. */
    public static TestLauncherFailure discovery(String moduleLabel, int exit, String output) {
        return new TestLauncherFailure(moduleLabel, "test discovery", exit, output);
    }

    /** A suite-running fork (the single runner or every pool worker) died before its first event. */
    public static TestLauncherFailure runner(String moduleLabel, int exit, String output) {
        return new TestLauncherFailure(moduleLabel, "test runner", exit, output);
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
        return jvm != null ? jvm : trace;
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
     * {@code Metaspace} says nothing without its {@code OutOfMemoryError}.
     */
    private static String headlineSuffix(@Nullable String output) {
        String header = header(output);
        if (header == null || header.isBlank()) return "";
        if (!fromRunner(output)) return " — " + header;
        int colon = header.indexOf(": ");
        String headline =
                colon >= 0 && CLASS_NAME.matcher(header.substring(0, colon)).matches()
                        ? header.substring(colon + 2).strip()
                        : header;
        return headline.isBlank() ? "" : " — " + headline;
    }

    private static boolean fromRunner(@Nullable String output) {
        if (output == null) return false;
        for (String line : output.split("\n")) {
            if (line.strip().startsWith(RUNNER_PREFIX)) return true;
        }
        return false;
    }
}
