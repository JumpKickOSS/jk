// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code jk} command a run configuration stands for. A test runs {@code jk test -m <module>
 * --class <fqcn>} from the workspace root; an application runs {@code jk run <module>}. Under
 * the Debug executor the command carries {@code --debug-jvm=localhost:<port>}: jk starts the one
 * JVM with a suspended JDWP listener at that address and the IDE's debugger attaches to it.
 */
public final class JkCommandLines {

    /** {@code jk test} of one class. */
    public static final String KIND_TEST = "test";

    /** {@code jk run} of the module's main class. */
    public static final String KIND_RUN = "run";

    private JkCommandLines() {}

    /**
     * The arguments after {@code jk}. {@code moduleRel} is the module directory relative to the
     * workspace root ({@code ""} for a standalone project); {@code debugAddress} is the
     * {@code host:port} a debugger attaches to, or {@code null} for a plain run.
     */
    public static List<String> args(
            String kind, String moduleRel, @Nullable String className, @Nullable String debugAddress) {
        List<String> args = new ArrayList<>();
        if (KIND_RUN.equals(kind)) {
            args.add("run");
            args.add(moduleRel.isEmpty() ? "." : moduleRel);
        } else {
            args.add("test");
            if (!moduleRel.isEmpty()) {
                args.add("-m");
                args.add(moduleRel);
            }
            if (className != null && !className.isBlank()) {
                args.add("--class");
                args.add(className);
            }
        }
        if (debugAddress != null) args.add("--debug-jvm=" + debugAddress);
        return args;
    }

    /** A loopback port nothing listens on right now; the JVM jk starts takes it a moment later. */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
