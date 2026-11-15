// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import cc.jumpkick.layout.BuildLayout;
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
 * Writes the order this JVM ran its test classes in, one file per test JVM under {@code
 * target/reports/class-order/}, appended.
 *
 * <p>An order-dependent failure is only investigable if the order is known, and the runner does
 * not record it: workers pull classes off a shared queue, so the assignment is decided by whichever
 * worker asks first and is gone the moment the run ends.
 *
 * <p>A runner may run several test plans per JVM, each in its own classloader with a fresh
 * listener, so the file is appended, never written whole. It lives in the module's build output:
 * a new entry beside the module's sources while the suite runs would move the listing its stamp
 * keys, and the suite would run again on the next build.
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
            // The pid is stable across the classloaders of one JVM's test plans.
            Path f = Path.of(
                    BuildLayout.TARGET,
                    "reports",
                    "class-order",
                    ProcessHandle.current().pid() + ".txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, String.join("\n", order) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // A missing record is not a reason to disturb a green suite.
        }
    }
}
