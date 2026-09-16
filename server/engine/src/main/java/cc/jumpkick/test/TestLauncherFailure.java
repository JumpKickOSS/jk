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

    /** The exception class the runner reported, or {@code ""} when its output names none. */
    public String exceptionClass() {
        String header = header(output);
        if (header == null) return "";
        int colon = header.indexOf(": ");
        String cls = colon < 0 ? header : header.substring(0, colon);
        return CLASS_NAME.matcher(cls).matches() ? cls : "";
    }

    /** The exception message the runner reported, or {@code ""}. */
    public String headline() {
        String header = header(output);
        if (header == null) return "";
        int colon = header.indexOf(": ");
        if (colon < 0 || !CLASS_NAME.matcher(header.substring(0, colon)).matches()) return header;
        return header.substring(colon + 2).strip();
    }

    /** The JUnit Platform engine id in {@code TestEngine with ID '…'}, or {@code null}. */
    public @Nullable String engineId() {
        Matcher m = ENGINE_ID.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /** The {@code caused by:} chain the runner printed, outermost first. */
    public List<String> causes() {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.startsWith(CAUSE_PREFIX))
                out.add(t.substring(CAUSE_PREFIX.length()).strip());
        }
        return List.copyOf(out);
    }

    /** The first runner header line without its prefixes, or {@code null} when the fork printed none. */
    private static @Nullable String header(@Nullable String output) {
        if (output == null) return null;
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (!t.startsWith(RUNNER_PREFIX)) continue;
            String rest = t.substring(RUNNER_PREFIX.length());
            if (rest.startsWith(DISCOVERY_PREFIX)) rest = rest.substring(DISCOVERY_PREFIX.length());
            return rest.strip();
        }
        return null;
    }

    private static String headlineSuffix(@Nullable String output) {
        String header = header(output);
        if (header == null || header.isBlank()) return "";
        int colon = header.indexOf(": ");
        String headline =
                colon >= 0 && CLASS_NAME.matcher(header.substring(0, colon)).matches()
                        ? header.substring(colon + 2).strip()
                        : header;
        return headline.isBlank() ? "" : " — " + headline;
    }
}
