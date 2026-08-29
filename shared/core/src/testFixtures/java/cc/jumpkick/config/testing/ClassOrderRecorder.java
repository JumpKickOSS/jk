// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes the order this JVM ran its test classes in, to {@code build/reports/jk-class-order.txt}.
 *
 * <p>An order-dependent failure is only investigable if the order is known, and neither Gradle nor
 * jk records it: workers pull classes off a shared queue, so the assignment is decided by whichever
 * worker asks first and is gone the moment the run ends. That is why JK-1003's flake could only be
 * described statistically and JK-1007's writer was never identified.
 *
 * <p>One file per test JVM under {@code build/reports/class-order/}, appended. Gradle runs several
 * test plans per JVM, each in its own classloader with a fresh listener, so writing the file whole
 * left only the last batch — 14 of 155 classes, measured — and a static "already truncated" flag
 * did not survive the classloader either.
 *
 * <p>Deliberately always on and deliberately dumb — an append-only list of class names, one file per
 * test JVM's working directory. It records what happened; it does not try to make it repeatable.
 * Seeding the assignment so a shard can be replayed exactly is a bigger change and is not this.
 */
public final class ClassOrderRecorder implements TestExecutionListener {

    private final List<String> order = new CopyOnWriteArrayList<>();

    @Override
    public void executionStarted(TestIdentifier id) {
        id.getSource()
                .filter(ClassSource.class::isInstance)
                .map(s -> ((ClassSource) s).getClassName())
                .ifPresent(order::add);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        // Nothing: the start order is the fact worth keeping.
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (order.isEmpty()) return;
        try {
            // One file per test JVM, always appended. A static "have I truncated yet" flag does
            // not survive: Gradle runs each test plan in its own classloader, so the flag reset and
            // every plan truncated, leaving 14 of 155 classes. The pid is stable across those
            // classloaders; the directory goes away with build/.
            Path f = Path.of(
                    "build", "reports", "class-order", ProcessHandle.current().pid() + ".txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.join("\n", order) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // A missing record is not a reason to disturb a green suite.
        }
    }
}
