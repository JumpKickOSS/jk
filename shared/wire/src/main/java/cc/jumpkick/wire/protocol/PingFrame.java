// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

/** Liveness probe (see {@link EngineProtocol#PING}); carries nothing but its type. */
public record PingFrame() {
    public String encode() {
        return RequestJson.request(EngineProtocol.PING).finish();
    }

    public static PingFrame decode(String json) {
        return new PingFrame();
    }
}
