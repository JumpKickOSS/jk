// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The flags a test JVM starts with when its failures carry a snapshot ({@code [test]
 * failure-snapshot}): the JDWP agent listening on a free loopback port without suspending, the
 * export that lets the test runner read that port, and the property that tells the runner to
 * attach to it. The runner reads the throwing frame's variables through it.
 */
final class FailureSnapshotFlags {

    /** The property the test runner reads; it spells the same name. */
    static final String PROPERTY = "jk.test.failure-snapshot";

    static final String AGENT = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0,quiet=y";

    static final String EXPORTS = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED";

    private FailureSnapshotFlags() {}

    /**
     * The flags for a test JVM of {@code javaHome} started with {@code jvmArgs}; none when those
     * arguments load a JDWP agent of their own (a JVM takes one) or the JDK has no debugger to
     * attach.
     */
    static List<String> flags(@Nullable Path javaHome, List<String> jvmArgs) {
        for (String arg : jvmArgs) if (arg.contains("jdwp")) return List.of();
        if (javaHome == null || !debuggable(javaHome)) return List.of();
        return List.of(AGENT, EXPORTS, "-D" + PROPERTY + "=true");
    }

    /**
     * Whether the JDK at {@code javaHome} has the JDWP agent and the {@code jdk.jdi} module, as its
     * {@code release} file lists them. A runtime image built without them, or one with no readable
     * list, would refuse to start with the agent, so it counts as having neither.
     */
    static boolean debuggable(Path javaHome) {
        String text;
        try {
            text = Files.readString(javaHome.resolve("release"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        for (String line : text.split("\\R")) {
            if (!line.startsWith("MODULES=")) continue;
            List<String> modules = List.of(
                    line.substring("MODULES=".length()).replace("\"", "").trim().split("\\s+"));
            return modules.contains("jdk.jdi") && modules.contains("jdk.jdwp.agent");
        }
        return false;
    }
}
