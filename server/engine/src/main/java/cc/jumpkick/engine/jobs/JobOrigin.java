// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import org.jspecify.annotations.Nullable;

/**
 * Who asked for a job: the surface it came in on ({@code trigger}) and, when that surface has one,
 * the {@code session} that asked — an MCP connection's client name and id, an IDE window's BSP
 * client. Journaled on the run record and rendered wherever the run is shown, so the person
 * supervising an agent can tell its runs from their own.
 */
public record JobOrigin(String trigger, @Nullable String session) {

    /** The dashboard's own actions: New project, Build, Rebuild. */
    public static final JobOrigin WEB = new JobOrigin("web", null);

    public JobOrigin {
        if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException("trigger is required");
        session = session == null || session.isBlank() ? null : session.trim();
    }

    /** An MCP tool call; {@code session} is the connection label, or null when the client sent no session id. */
    public static JobOrigin mcp(@Nullable String session) {
        return new JobOrigin("mcp", session);
    }
}
