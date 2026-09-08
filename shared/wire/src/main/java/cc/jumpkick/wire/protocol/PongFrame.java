// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

/** Liveness answer (see {@link EngineProtocol#PONG}); carries nothing but its type. */
public record PongFrame() {
    public String encode() {
        return RequestJson.request(EngineProtocol.PONG).finish();
    }

    public static PongFrame decode(String json) {
        return new PongFrame();
    }
}
