// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.model.Project;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Java level a new standalone project targets when nothing names one: the major of the
 * machine's default JDK ({@code jk jdk default}), so the project compiles for the JDK it builds
 * with. No default JDK leaves the template's own {@code java}.
 */
final class NewHostJava {

    /** Where the default JDK is recorded; tests swap in an inventory of their own. */
    static volatile Supplier<JdkInventory> inventory = JdkInventory::current;

    private NewHostJava() {}

    /** The default JDK's major as a {@code java} value, or {@code null} when there is none (never throws). */
    static @Nullable String major() {
        try {
            int major = inventory.get().defaultId().map(Project::majorOf).orElse(0);
            return major > 0 ? Integer.toString(major) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
