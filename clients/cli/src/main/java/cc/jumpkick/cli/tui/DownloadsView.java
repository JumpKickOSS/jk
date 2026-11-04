// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.task.TransferBoard;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A live region that draws the run's downloads. The engine samples its board onto every job's
 * stream, whatever the verb, and {@code WireStream} hands each frame to the region on screen when it
 * is one of these — so every command that has a region shows its downloads the same way.
 */
public interface DownloadsView {

    /** The run's downloads now; called from the stream's reader thread. */
    void downloads(TransferBoard.Snapshot snapshot);

    /**
     * Install {@code view} as the one that takes the run's downloads when no region is on screen:
     * the JSONL writer of a {@code --output json} command. Null uninstalls.
     */
    static void fallback(@Nullable DownloadsView view) {
        Holder.FALLBACK.set(view);
    }

    /** The view that takes downloads now: the region on screen when it draws them, else the fallback. */
    static @Nullable DownloadsView current() {
        if (LiveRegion.active() instanceof DownloadsView region) return region;
        return Holder.FALLBACK.get();
    }

    /** Encapsulates the mutable slot so the interface exposes only methods. */
    final class Holder {
        private Holder() {}

        private static final AtomicReference<@Nullable DownloadsView> FALLBACK = new AtomicReference<>();
    }
}
