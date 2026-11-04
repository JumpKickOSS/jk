// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The workspace's code-graph refresh slot, {@code target/.jk/graph-refresh.lock}. A build that
 * succeeds refreshes the workspace's graph on its own thread so it reports without waiting, and the
 * refresh writes under {@code target/} (class indexes, the graph's own output) after the build has
 * returned. The build takes this slot before it returns and the refresh lets it go when it is done,
 * so {@code jk clean} can wait for it instead of racing it.
 */
public final class GraphRefreshSlot {

    public static final String LOCK_FILE = "graph-refresh.lock";

    private static final long POLL_MILLIS = 50;

    private GraphRefreshSlot() {}

    /** Where the refresh slot of the workspace at {@code root} lives. */
    public static Path lockFile(Path root) {
        return root.resolve(BuildLayout.TARGET).resolve(".jk").resolve(LOCK_FILE);
    }

    /** Wait up to {@code max} for no refresh to hold {@code root}'s slot; whether it came free. */
    public static boolean awaitFree(Path root, Duration max) {
        long deadline = Clock.SYSTEM.nanos() + max.toNanos();
        while (true) {
            switch (FileLocks.tryHold(lockFile(root))) {
                case FileLocks.Hold hold -> {
                    try {
                        hold.close();
                    } catch (IOException ignored) {
                        // a probe's hold; nothing depends on it
                    }
                    return true;
                }
                case FileLocks.Unavailable unavailable -> {
                    // No target/.jk, or no locks here: no refresh can be holding it.
                    return true;
                }
                case FileLocks.Held held -> {
                    if (Clock.SYSTEM.nanos() >= deadline) return false;
                    try {
                        Thread.sleep(POLL_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
    }
}
