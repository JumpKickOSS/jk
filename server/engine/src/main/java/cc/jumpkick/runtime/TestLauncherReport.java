// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.test.TestLauncherFailure;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code run-tests} step records when the forked launcher never ran a test: one
 * {@code test-launcher} diagnostic — the exit, the exception and engine the runner named, the
 * cause chain with its first frames, the classes discovery could not load, the two conflicting
 * JUnit coordinates when the lock names them, and the repair —
 * with the fork's output as its stack. It renders as a failed step in {@code jk-results.md} and
 * as one row of {@code diagnostics}, never as a red test.
 */
final class TestLauncherReport {

    static final String CODE = "test-launcher";

    private TestLauncherReport() {}

    static void report(TaskContext ctx, Path lockFile, JkBuild project, TestLauncherFailure e) {
        JUnitLineConflict.Conflict conflict = JUnitLineConflict.describe(project, readLock(lockFile));
        String message = message(e, conflict);
        ctx.error(
                CODE,
                message,
                new TestFailureInfo(
                        e.moduleLabel(),
                        e.engineId() == null ? "" : e.engineId(),
                        "",
                        "",
                        e.exceptionClass(),
                        message,
                        e.output()));
    }

    /** The diagnostic text: headline, causes, the conflict when known, then the fix. */
    static String message(TestLauncherFailure e, JUnitLineConflict.@Nullable Conflict conflict) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        String engine = e.engineId();
        if (engine != null) sb.append("\nengine: ").append(engine);
        for (String cause : e.causes()) sb.append("\ncaused by: ").append(cause);
        for (String frame : e.frames()) sb.append("\n    at ").append(frame);
        // A fork that said nothing is diagnosed from how it was started.
        if (e.lastLines().isEmpty() && !e.command().isEmpty())
            sb.append("\ncommand: ").append(e.commandLine());
        List<String> dropped = e.droppedClasses();
        if (conflict != null) {
            sb.append("\n\n").append(conflict.text());
            sb.append("\n\nFix: `jk why ")
                    .append(conflict.coordinate())
                    .append("` names who asked for each version; put the line on one version, then `jk lock`");
        } else if (!dropped.isEmpty()) {
            sb.append("\n\nFix: the test JVM could not load ")
                    .append(String.join(", ", dropped))
                    .append(": `")
                    .append(e.rootCause())
                    .append(
                            "`\nits supertypes must be on the test classpath; @QuarkusTest also needs the app to build");
        } else if (engine != null) {
            sb.append("\n\nFix: the ")
                    .append(engine)
                    .append(" engine could not start; every JUnit artifact needs one version\n`jk why ")
                    .append(engineCoordinate(engine))
                    .append("` shows where each comes from");
        } else if (e.signal() != null) {
            sb.append("\n\nFix: the ")
                    .append(e.phase())
                    .append(" JVM was killed by ")
                    .append(e.signal())
                    .append(" before it ran a test")
                    .append(e.lastLines().isEmpty() ? " and printed nothing" : "")
                    .append(
                            "SIGKILL".equals(e.signal())
                                    ? "\nlikely the out-of-memory killer; lower `[test] workers` or the -Xmx in"
                                            + " `[test] jvm-args`"
                                    : "\nthe JVM crashed; its hs_err file names where");
        } else if (e.jvmRefused()) {
            sb.append("\n\nFix: the ")
                    .append(e.phase())
                    .append(" JVM refused to start: ")
                    .append(e.headline())
                    .append("\ncheck the flags in `[test] jvm-args`, or lower `[test] workers` or -Xmx");
        } else if (e.outOfMemory()) {
            sb.append("\n\nFix: the ")
                    .append(e.phase())
                    .append(" JVM ran out of memory (")
                    .append(e.headline())
                    .append(") before it ran a test\nraise -Xmx, or raise or drop -XX:MaxMetaspaceSize, in"
                            + " `[test] jvm-args`");
        } else if (!e.rootCause().isEmpty()) {
            sb.append("\n\nFix: the test framework failed before reporting: `")
                    .append(e.rootCause())
                    .append("`\nthe frames above show where; rerun with -v for the full output");
        } else if (e.lastLines().isEmpty()) {
            sb.append("\n\nFix: the test JVM printed nothing; check `[test] jvm-args`, its agents and the test JDK"
                    + "\nrerun with -v for the full output");
        } else {
            sb.append("\n\nFix: see the runner output above; rerun with -v for the full output");
        }
        return sb.toString();
    }

    private static String engineCoordinate(String engineId) {
        return switch (engineId) {
            case "junit-jupiter" -> "org.junit.jupiter:junit-jupiter-engine";
            case "junit-vintage" -> "org.junit.vintage:junit-vintage-engine";
            case "testng" -> "org.junit.support:testng-engine";
            default -> engineId;
        };
    }

    private static @Nullable Lockfile readLock(Path lockFile) {
        try {
            return LockfileReader.read(lockFile);
        } catch (Exception e) {
            return null;
        }
    }
}
