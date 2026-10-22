// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ports for a server a test starts later, after a build. A port the kernel hands out for port 0 is
 * in the ephemeral range, where any outgoing connection can take it before the server binds; these
 * come from {@value #LOW}-{@value #HIGH}, below the ephemeral range on Linux, macOS and Windows,
 * picked at random so parallel test JVMs rarely meet, checked free when chosen, and never handed
 * out twice in one JVM.
 */
public final class DevPorts {

    private DevPorts() {}

    static final int LOW = 20_000;
    static final int HIGH = 32_000;

    private static final Set<Integer> ISSUED = new LinkedHashSet<>();

    /** {@code n} distinct ports free right now. */
    public static synchronized int[] free(int n) throws IOException {
        Set<Integer> chosen = new LinkedHashSet<>();
        for (int tries = 0; chosen.size() < n; tries++) {
            if (tries > 1_000) throw new IOException("no free port in " + LOW + "-" + HIGH);
            int port = ThreadLocalRandom.current().nextInt(LOW, HIGH);
            if (!ISSUED.contains(port) && !chosen.contains(port) && bindable(port)) chosen.add(port);
        }
        ISSUED.addAll(chosen);
        return chosen.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One port free right now. */
    public static int free() throws IOException {
        return free(1)[0];
    }

    private static boolean bindable(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }
}
