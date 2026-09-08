// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Outer invocation phase: the phase's wire name and {@code start}|{@code finish} (see {@link EngineProtocol#INVOCATION_PHASE}). */
public record InvocationPhaseEvent(String phase, String status) {
    public String encode() {
        return RequestJson.request(EngineProtocol.INVOCATION_PHASE)
                .string("phase", phase, "")
                .string("status", status, "")
                .finish();
    }

    public static InvocationPhaseEvent decode(String json) {
        return new InvocationPhaseEvent(Jsonl.str(json, "phase"), Jsonl.str(json, "status"));
    }
}
