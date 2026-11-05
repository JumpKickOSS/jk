// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

/** Successor → displaced predecessor: stop writing session records (see {@link EngineProtocol#STOP_SESSIONS}). */
public record StopSessionsFrame() {
    public String encode() {
        return RequestJson.request(EngineProtocol.STOP_SESSIONS).finish();
    }

    public static StopSessionsFrame decode(String json) {
        return new StopSessionsFrame();
    }
}
