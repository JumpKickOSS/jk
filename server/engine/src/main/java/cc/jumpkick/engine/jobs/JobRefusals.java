// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.jobs.JobEnvelope.AlreadyRunning;
import cc.jumpkick.engine.jobs.JobEnvelope.Host;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * How {@link JobEnvelope} turns a job away before it runs: the {@code request-finish} that resolves
 * its dashboard card and ends a watcher's wait, the client's terminal on a socket submission, and
 * the exception a detached one raises.
 */
final class JobRefusals {

    private final Host host;

    JobRefusals(Host host) {
        this.host = host;
    }

    /** The {@code request-finish} of a job that never ran. */
    private void publishFinish(long jid, String kind, String dir, boolean cancelled, @Nullable String error) {
        JsonOut finish = JsonOut.object()
                .put("schema", 1)
                .put("type", "request-finish")
                .put("jid", jid)
                .put("kind", kind)
                .put("dir", dir)
                .put("projectId", ProjectIds.idOf(dir))
                .put("success", false)
                .put("cancelled", cancelled);
        if (error != null) finish.put("error", error);
        host.publishEvent("request-finish", finish.put("millis", 0L).put("activeBuildPlans", host.activeBuildPlans()));
    }

    /**
     * A refusal that came after the caller already had the jid: the ones {@link #refuse} sends are
     * events already; an already-running or draining refusal is published here, so whoever watches
     * the jid sees it end.
     */
    void publishLateRefusal(long jid, String kind, String dir, RuntimeException refused) {
        if (!(refused instanceof AlreadyRunning) && !host.draining()) return;
        host.log("jk engine: job " + jid + " (" + kind + " " + dir + ") " + refused.getMessage());
        publishFinish(jid, kind, dir, false, String.valueOf(refused.getMessage()));
    }

    /** A refusal before the job ran: one log line, the dashboard card resolved, the error to the client. */
    long refuse(
            long jid,
            String kind,
            String dir,
            boolean detached,
            @Nullable BufferedWriter writer,
            String code,
            String message) {
        host.log("jk engine: job " + jid + " (" + kind + " " + dir + ") " + message);
        publishFinish(jid, kind, dir, false, message);
        if (detached) throw new IllegalStateException(message);
        // A refusal's terminal has landed before submit returns, as a job's finish has: the
        // connection loop may close the socket the moment it gets it back.
        if (writer != null) {
            WireWriter.sendQuiet(writer, ProtoLifecycle.error(code, message));
            WireWriter.awaitLanded(writer);
        }
        return -1;
    }

    /**
     * Cancelled while waiting: nothing ran, so the stream ends on the cancelled terminal its shape
     * expects and the dashboard card resolves through {@code request-finish} — no journal row was
     * ever begun for it.
     */
    long refuseCancelledInQueue(
            long jid,
            String kind,
            String dir,
            boolean workspaceStream,
            boolean detached,
            @Nullable BufferedWriter writer) {
        publishFinish(jid, kind, dir, true, null);
        if (detached) throw new IllegalStateException("cancelled while waiting for engine memory");
        if (writer != null) {
            WireWriter.sendQuiet(writer, LiveJobRegistry.cancelledTerminalLine(workspaceStream, dir));
            WireWriter.awaitLanded(writer);
        }
        return -1;
    }

    /** The engine is draining: a detached submit throws, a socket submit tells the client and returns -1. */
    static long refuseDraining(boolean detached, @Nullable BufferedWriter writer) {
        if (detached) throw new IllegalStateException("engine is shutting down");
        try {
            if (writer != null)
                WireWriter.send(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_SHUTTING_DOWN,
                                "the engine is shutting down; retry to start a new one"));
            if (writer != null) WireWriter.awaitLanded(writer);
        } catch (IOException ignored) {
            // Client vanished mid-refusal — nothing to do; the connection is closing anyway.
        }
        return -1;
    }

    /** A same-fingerprint job holds the slot: give back the plan slot nothing ran on, then refuse. */
    long refuseAlreadyRunning(
            InFlightBuilds.Hold h,
            String eventKind,
            boolean detached,
            boolean claimedBuildPlanSlot,
            @Nullable BufferedWriter writer) {
        String label = "test".equals(eventKind) ? "Test" : "Build";
        String msg = label + (h.buildNumber() > 0 ? " #" + h.buildNumber() : "") + " is already running";
        if (detached) {
            if (claimedBuildPlanSlot) host.abandonBuildPlanSlot();
            throw new AlreadyRunning(msg, h.requestId(), h.buildNumber());
        }
        try {
            if (writer != null) {
                WireWriter.send(writer, ProtoLifecycle.alreadyRunning(h.buildNumber(), h.requestId(), msg));
                WireWriter.awaitLanded(writer);
            }
        } catch (IOException ignored) {
            // client gone
        }
        if (claimedBuildPlanSlot) host.abandonBuildPlanSlot(); // nothing ran — give the slot back
        return -1;
    }
}
