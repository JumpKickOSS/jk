// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Objects;

/**
 * The terminal task an invocation is trying to reach. The BuildPlan is the transitive closure of
 * tasks required to produce this target.
 *
 * @see docs/features/build-plan.md
 */
public final class Target {

    private final String taskName;

    private Target(String taskName) {
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        if (taskName.isBlank()) throw new IllegalArgumentException("target task name must be non-blank");
    }

    /** Target that terminates at the given task name (e.g. {@link TaskNames#PACKAGE_JAR}). */
    public static Target of(String taskName) {
        return new Target(taskName);
    }

    public String taskName() {
        return taskName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Target t && taskName.equals(t.taskName);
    }

    @Override
    public int hashCode() {
        return taskName.hashCode();
    }

    @Override
    public String toString() {
        return "Target(" + taskName + ")";
    }
}
