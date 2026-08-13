// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

/**
 * One hosted job submission. All fields required — no boolean flags, no overload tower.
 */
public record JobRequest(JobKind kind, String threadPrefix, JobBody body) {
    public JobRequest {
        if (kind == null) throw new IllegalArgumentException("kind");
        if (threadPrefix == null || threadPrefix.isBlank()) throw new IllegalArgumentException("threadPrefix");
        if (body == null) throw new IllegalArgumentException("body");
    }

    public String verb() {
        return kind.verb();
    }

    public boolean joinsActivePlans() {
        return kind.joinsActivePlans();
    }

    public boolean workspaceTerminal() {
        return kind.workspaceTerminal();
    }

    public static JobRequest workspace(String verb, String threadPrefix, JobBody body) {
        return new JobRequest(JobKind.workspace(verb), threadPrefix, body);
    }

    public static JobRequest plan(String verb, String threadPrefix, JobBody body) {
        return new JobRequest(JobKind.plan(verb), threadPrefix, body);
    }

    public static JobRequest maintenance(String verb, String threadPrefix, JobBody body) {
        return new JobRequest(JobKind.maintenance(verb), threadPrefix, body);
    }
}
