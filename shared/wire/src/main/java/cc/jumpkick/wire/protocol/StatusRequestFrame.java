// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

/** Client → server: report the engine's vitals (see {@link EngineProtocol#STATUS}); carries nothing but its type. */
public record StatusRequestFrame() {
    public String encode() {
        return RequestJson.request(EngineProtocol.STATUS).finish();
    }

    public static StatusRequestFrame decode(String json) {
        return new StatusRequestFrame();
    }
}
