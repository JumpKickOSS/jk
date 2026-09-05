// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Terminal for {@link EngineProtocol#NEW_PROJECT_REQUEST}. */
public record NewProjectAck(@Nullable String error, String path, String projectId, int filesWritten) {

    public static NewProjectAck error(String message) {
        return new NewProjectAck(message, "", "", 0);
    }

    public static NewProjectAck of(String path, @Nullable String projectId, int filesWritten) {
        return new NewProjectAck(null, path, projectId == null ? "" : projectId, filesWritten);
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.NEW_PROJECT_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"path\":" + Jsonl.quote(path)
                + ",\"projectId\":" + Jsonl.quote(projectId)
                + ",\"filesWritten\":" + filesWritten
                + "}";
    }

    public static NewProjectAck decode(String line) {
        return new NewProjectAck(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "path")),
                orEmpty(Jsonl.str(line, "projectId")),
                Jsonl.intValue(line, "filesWritten", 0));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
