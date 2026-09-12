// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import java.io.BufferedWriter;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
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
        /** The job's socket; its read side is half-closed to wake the connection thread off readLine. */
        @Nullable SocketChannel channel,
        /** Connection thread parked on client readLine — woken so teardown can run. */
        @Nullable Thread connectionThread,
        /**
         * Released when a user cancel begins, so the thread joining the runner — a detached
         * job's joiner has no socket to read EOF from — switches to the bounded post-cancel join.
         */
        CountDownLatch cancelSignal,
        String dir,
        String kind,
        /**
         * True when the stream's terminal line is {@code workspace-finish}; false for single
         * plans (single build, test, lock, …), whose client loop only ends on {@code plan-finish}.
         */
        boolean workspaceStream) {}
