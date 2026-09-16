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
 * as one row of {@code jk_diagnostics}, never as a red test.
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
        List<String> dropped = e.droppedClasses();
        if (conflict != null) {
            sb.append("\n\n").append(conflict.text());
            sb.append("\n\nFix: `jk why ")
                    .append(conflict.coordinate())
                    .append("` names who asked for each version; align the pin with the platform line"
                            + " (one version for every artifact of the line), then `jk lock`.");
        } else if (!dropped.isEmpty()) {
            sb.append("\n\nFix: the test JVM could not load ")
                    .append(dropped.size() == 1 ? "the class " : "the classes ")
                    .append(String.join(", ", dropped))
                    .append(" — `")
                    .append(e.rootCause())
                    .append("`; the frames above name where. Loading a test class needs its supertypes on the test"
                            + " classpath (`jk why <artifact>` names who brings one) and, for a framework that boots"
                            + " the application while loading it (`@QuarkusTest`), an application that builds."
                            + " Rerun with --verbose for the runner's own output.");
        } else if (engine != null) {
            sb.append("\n\nFix: the ")
                    .append(engine)
                    .append(" engine could not start on this test classpath — `jk why ")
                    .append(engineCoordinate(engine))
                    .append("` shows the line it came from; every JUnit artifact must sit on one version.");
        } else if (e.outOfMemory()) {
            sb.append("\n\nFix: the ")
                    .append(e.phase())
                    .append(" JVM ran out of memory (")
                    .append(e.headline())
                    .append(") before it ran a test; the frames above name the framework that filled it — a test"
                            + " framework that starts the application while classes are still being listed. Raise"
                            + " the limit with `[test] jvm-args` (`-XX:MaxMetaspaceSize=1g`, `-Xmx…`), which the"
                            + " discovery JVM honours too.");
        } else if (!e.rootCause().isEmpty()) {
            sb.append("\n\nFix: the runner did not get to report; the failure is the framework's own — `")
                    .append(e.rootCause())
                    .append("` — and the frames above name where. Rerun with --verbose for the live stream.");
        } else {
            sb.append("\n\nFix: the runner's full output is above; rerun with --verbose for the live stream.");
        }
        return sb.toString();
    }

    private static String engineCoordinate(String engineId) {
        return switch (engineId) {
            case "junit-jupiter" -> "org.junit.jupiter:junit-jupiter-engine";
            case "junit-vintage" -> "org.junit.vintage:junit-vintage-engine";
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
