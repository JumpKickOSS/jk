// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.task.TransferBoard;

/**
 * A live region that draws the run's downloads. The engine samples its board onto every job's
 * stream, whatever the verb, and {@code WireStream} hands each frame to the region on screen when it
 * is one of these — so every command that has a region shows its downloads the same way.
 */
public interface DownloadsView {

    /** The run's downloads now; called from the stream's reader thread. */
    void downloads(TransferBoard.Snapshot snapshot);
}
