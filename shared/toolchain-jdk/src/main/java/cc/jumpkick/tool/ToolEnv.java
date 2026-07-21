// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.model.Coordinate;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Resolved tool: primary coord, launcher name, main class, and CAS classpath paths. For
 * {@link ToolLauncher} install/exec.
 */
public record ToolEnv(String binName, Coordinate primary, String mainClass, List<Path> classpath) {

    /** {@link #mainClass} sentinel: {@link #classpath} is the native binary to exec (no JVM). */
    public static final String NATIVE_BINARY = "native-binary";

    /** True when this env execs a native binary instead of {@code java -cp}. */
    public boolean isNativeBinary() {
        return NATIVE_BINARY.equals(mainClass);
    }

    public ToolEnv {
        Objects.requireNonNull(binName, "binName");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(classpath, "classpath");
        classpath = List.copyOf(classpath);
    }
}
