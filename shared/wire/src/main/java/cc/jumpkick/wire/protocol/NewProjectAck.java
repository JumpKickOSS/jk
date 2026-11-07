// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for {@link EngineProtocol#NEW_PROJECT_REQUEST}. {@code agentFiles} maps each file the
 * agent wiring changed to the verb read before it ({@code Wrote}), in order; {@code agentNotes} are
 * the steps it left to the user. Both are empty when the request wired nothing.
 */
public record NewProjectAck(
        @Nullable String error,
        String path,
        String projectId,
        int filesWritten,
        Map<String, String> agentFiles,
        List<String> agentNotes) {

    public NewProjectAck {
        agentFiles = agentFiles == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(agentFiles));
        agentNotes = agentNotes == null ? List.of() : List.copyOf(agentNotes);
    }

    public NewProjectAck(@Nullable String error, String path, String projectId, int filesWritten) {
        this(error, path, projectId, filesWritten, Map.of(), List.of());
    }

    public static NewProjectAck error(String message) {
        return new NewProjectAck(message, "", "", 0);
    }

    public static NewProjectAck of(String path, @Nullable String projectId, int filesWritten) {
        return of(path, projectId, filesWritten, Map.of(), List.of());
    }

    public static NewProjectAck of(
            String path,
            @Nullable String projectId,
            int filesWritten,
            Map<String, String> agentFiles,
            List<String> agentNotes) {
        return new NewProjectAck(null, path, projectId == null ? "" : projectId, filesWritten, agentFiles, agentNotes);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.NEW_PROJECT_ACK)
                .string("error", error)
                .string("path", path)
                .string("projectId", projectId)
                .number("filesWritten", filesWritten)
                .optionalMap("agentFiles", agentFiles)
                .optionalArray("agentNotes", agentNotes)
                .finish();
    }

    public static NewProjectAck decode(String line) {
        return new NewProjectAck(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "path")),
                orEmpty(Jsonl.str(line, "projectId")),
                Jsonl.intValue(line, "filesWritten", 0),
                Jsonl.strMap(line, "agentFiles"),
                Jsonl.strArray(line, "agentNotes"));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
