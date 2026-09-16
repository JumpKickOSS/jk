// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Command session opened: the command, its argv, and the origin the run record will carry — the
 * {@code trigger} ({@code cli}, {@code bsp}, {@code ci}) and the {@code session} that asked when
 * the requester has one.
 */
public record SessionStartLine(
        long ts,
        String command,
        List<String> argv,
        @Nullable String trigger,
        @Nullable String session) {
    public String encode() {
        return JsonlEnvelope.open(ts, "session-start")
                .string("command", command)
                .array("argv", argv)
                .optionalNonBlankString("trigger", trigger)
                .optionalNonBlankString("session", session)
                .finish();
    }

    public static SessionStartLine decode(String json) {
        return new SessionStartLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "command"),
                Jsonl.strArray(json, "argv"),
                Jsonl.str(json, "trigger"),
                Jsonl.str(json, "session"));
    }
}
