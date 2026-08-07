// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import java.io.IOException;

/** Typed wire error: preserves the {@code code} from {@code {"type":"error","code":...}} . */
public final class EngineWireException extends IOException {
    private final String code;

    public EngineWireException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? EngineProtocol.ERR_REQUEST_FAILED : code;
    }

    public String code() {
        return code;
    }

    @Override
    public String getMessage() {
        String m = super.getMessage();
        return "[" + code + "] " + (m == null ? "" : m);
    }

    public static EngineWireException fromJsonLine(String line) {
        String code = cc.jumpkick.plugin.protocol.Jsonl.str(line, "code");
        String msg = cc.jumpkick.plugin.protocol.Jsonl.str(line, "message");
        return new EngineWireException(code, msg);
    }
}
