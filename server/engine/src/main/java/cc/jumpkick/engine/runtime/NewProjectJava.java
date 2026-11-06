// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.model.Project;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Java level a new standalone project targets when nothing names one: the major of the
 * machine's default JDK ({@code jk jdk default}), so the project compiles for the JDK it builds
 * with. The engine runs on the caller's machine, so every surface ({@code jk new}, MCP {@code new},
 * {@code POST /api/projects}) gets the same answer. No default JDK leaves the template's own
 * {@code java}.
 */
public final class NewProjectJava {

    private static volatile Supplier<JdkInventory> inventory = JdkInventory::current;

    private NewProjectJava() {}

    /** The default JDK's major as a {@code java} value, or {@code null} when there is none (never throws). */
    public static @Nullable String hostMajor() {
        try {
            int major = inventory.get().defaultId().map(Project::majorOf).orElse(0);
            return major > 0 ? Integer.toString(major) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Test seam: reads the default JDK from {@code source}; {@code null} restores the machine's. */
    public static void inventory(@Nullable Supplier<JdkInventory> source) {
        inventory = source == null ? JdkInventory::current : source;
    }
}
