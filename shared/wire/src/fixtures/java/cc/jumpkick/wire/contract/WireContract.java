// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.contract;

import java.util.List;
import java.util.Set;

/**
 * The exchanges the engine and its clients are both tested against. The engine's suite feeds each
 * request to its real connection code and checks the answer conforms; a client's suite points its
 * real code at a {@link WireContractStub} serving the same answers and checks what it wrote and
 * what it made of them. A case added here fails every side that does not run it yet.
 *
 * <p>The cases are those an engine with no job running answers, so the engine's fast tier can
 * serve them without starting a build.
 */
public final class WireContract {

    private WireContract() {}

    /** A client's {@code hello}; the engine names itself. */
    public static final WireCase HELLO =
            new WireCase("hello", """
            {"type":"hello","version":"9.9.9","proto":1,"purpose":"connect"}""", List.of("""
                    {"type":"hello-ack","version":"9.9.9-contract","pid":4242,"startedAt":1767225600000,\
                    "proto":1,"draining":false,"buildId":"0a1b2c3d"}"""), Set.of("version", "purpose", "pid", "startedAt", "buildId"));

    /** The liveness probe. */
    public static final WireCase PING = new WireCase("ping", """
            {"type":"ping"}""", List.of("""
                    {"type":"pong"}"""), Set.of());

    /** {@code jk engine status}: the engine's vitals and its job listing. */
    public static final WireCase STATUS = new WireCase(
            "status",
            """
            {"type":"status"}""",
            List.of("""
                    {"type":"status-ack","version":"9.9.9-contract","pid":4242,"startedAt":1767225600000,\
                    "activeRequests":1,"activeBuildPlans":0,"queuedBuildPlans":0,"idleDropped":3,\
                    "heapUsedBytes":104857600,"heapCommittedBytes":209715200,"heapMaxBytes":1073741824,\
                    "rssBytes":314572800,"cores":8,"totalMemoryBytes":17179869184,\
                    "availableMemoryBytes":8589934592,"systemCpuLoad":0.25,"systemLoadAverage":1.5,\
                    "engineEpoch":"9.9.9-contract-1767225600000","logBytes":4096,"logRolledAt":-1,\
                    "jobs":[{"jid":7,"kind":"build","dir":"/contract/app","state":"live","since":1767225601000,\
                    "workers":2,"lastEventAt":1767225602000,"ahead":-1}],\
                    "identity":{"id":"engine-0001","label":"Linux on contract-host","commit":"0a1b2c3d"},\
                    "proto":1,"draining":false,"drainDeadline":-1,"httpUrl":null,"httpError":null,\
                    "mcpUrl":null}"""),
            Set.of(
                    "version",
                    "pid",
                    "startedAt",
                    "activeRequests",
                    "idleDropped",
                    "heapUsedBytes",
                    "heapCommittedBytes",
                    "heapMaxBytes",
                    "rssBytes",
                    "cores",
                    "totalMemoryBytes",
                    "availableMemoryBytes",
                    "systemCpuLoad",
                    "systemLoadAverage",
                    "engineEpoch",
                    "logBytes",
                    "logRolledAt",
                    "jobs",
                    "identity"));

    /** {@code jk cancel <jid>} for a job the engine does not have. */
    public static final WireCase CANCEL_UNKNOWN_JID = new WireCase("cancel-unknown-jid", """
            {"type":"cancel-request","jid":99}""", List.of("""
                    {"type":"cancel-ack","jid":99,"cancelled":false,"note":"unknown or already finished jid"}"""), Set.of());

    /** Bare {@code jk cancel} (and Ctrl-C) in a project with no job running. */
    public static final WireCase CANCEL_IDLE_DIR = new WireCase("cancel-idle-dir", """
            {"type":"cancel-request","dir":"/contract/app"}""", List.of("""
                    {"type":"cancel-ack","jid":0,"cancelled":false,"note":"no running jobs for dir"}"""), Set.of());

    /** Ctrl-Z for a job the engine does not have. */
    public static final WireCase SUSPEND_UNKNOWN_JID = new WireCase("suspend-unknown-jid", """
            {"type":"job-control-request","jid":99,"action":"suspend"}""", List.of("""
                    {"type":"job-control-ack","jid":99,"applied":false}"""), Set.of());

    /** Every case, in the order the suites run them. */
    public static final List<WireCase> CASES =
            List.of(HELLO, PING, STATUS, CANCEL_UNKNOWN_JID, CANCEL_IDLE_DIR, SUSPEND_UNKNOWN_JID);
}
