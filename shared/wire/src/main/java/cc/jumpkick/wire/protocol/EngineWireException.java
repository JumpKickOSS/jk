// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;

/**
 * Typed wire error: preserves the {@code code} from {@code {"type":"error","code":...}} so callers
 * can branch on it (e.g. {@link #alreadyRunning()}), while {@link #getMessage()} stays the plain
 * human message the engine sent — no raw wire code leaks into CLI output.
 */
public final class EngineWireException extends IOException {
    private final String code;

    public EngineWireException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? EngineProtocol.ERR_REQUEST_FAILED : code;
    }

    public String code() {
        return code;
    }

    /** True for {@link EngineProtocol#ERR_ALREADY_RUNNING} — a build/test already running. */
    public boolean alreadyRunning() {
        return EngineProtocol.ERR_ALREADY_RUNNING.equals(code);
    }

    public static EngineWireException fromJsonLine(String line) {
        String code = Jsonl.str(line, "code");
        String msg = Jsonl.str(line, "message");
        return new EngineWireException(code, msg);
    }

    /** Same as {@link #fromJsonLine(String)}, with a human-readable context prefix. */
    public static EngineWireException fromJsonLine(String line, String contextPrefix) {
        EngineWireException e = fromJsonLine(line);
        String msg = e.getMessage();
        return new EngineWireException(e.code(), contextPrefix + (msg == null ? "" : msg));
    }
}
