// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.IOException;

/**
 * The engine (or its socket) ended a live job after a user cancel without a clean terminal line
 * . Callers settle the TUI as "Build job was cancelled" rather than a crash.
 */
public final class JobCancelledException extends IOException {

    public JobCancelledException() {
        super("Build job was cancelled");
    }
}
