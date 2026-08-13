// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import java.io.BufferedWriter;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * One admitted job in the cancel registry. Kind alone cannot tell the terminal shape: single
 * builds journal as {@code "build"} too.
 */
public record LiveJob(
        Session.CancelToken token,
        AtomicReference<Thread> runnerRef,
        /** Streaming socket for this job — used to push an immediate cancelled terminal. */
        @Nullable BufferedWriter writer,
        /** Connection thread parked on client readLine — interrupted so teardown can run. */
        @Nullable Thread connectionThread,
        String dir,
        String kind,
        /**
         * True when the stream's terminal line is {@code workspace-finish}; false for single
         * plans (single build, test, lock, …), whose client loop only ends on {@code plan-finish}.
         */
        boolean workspaceStream) {}
