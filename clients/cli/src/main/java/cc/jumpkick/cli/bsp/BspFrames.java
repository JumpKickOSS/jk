// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.jsonl.JsonFields;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The server's outgoing JSON-RPC frames on stdout. The reader thread and the job worker both
 * write, so each frame goes out whole under this writer's own lock, which is held for nothing but
 * the write: a slow engine call never waits behind it, and it never waits behind one.
 */
final class BspFrames {

    private final OutputStream out;
    private final Object writeLock = new Object();

    BspFrames(OutputStream out) {
        this.out = out;
    }

    /** The result of request {@code id}; a notification ({@code id} null) takes no reply. */
    void respond(@Nullable String id, String resultJson) throws IOException {
        if (id == null) return;
        write(JsonFields.object()
                .string("jsonrpc", "2.0")
                .token("id", id)
                .token("result", resultJson)
                .finish());
    }

    void notify(String method, String paramsJson) throws IOException {
        write(JsonFields.object()
                .string("jsonrpc", "2.0")
                .string("method", method)
                .token("params", paramsJson)
                .finish());
    }

    void error(String id, int code, String message) throws IOException {
        write(JsonFields.object()
                .string("jsonrpc", "2.0")
                .token("id", id)
                .token(
                        "error",
                        JsonFields.object()
                                .number("code", code)
                                .string("message", message)
                                .finish())
                .finish());
    }

    /** One frame: ASCII headers, a blank line, then the body whose byte count the header carries. */
    private void write(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            out.write(header);
            out.write(bytes);
            out.flush();
        }
    }
}
