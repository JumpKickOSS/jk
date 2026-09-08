// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/** Command session opened: the command and its argv. */
public record SessionStartLine(long ts, String command, List<String> argv) {
    public String encode() {
        return JsonlEnvelope.open(ts, "session-start")
                .string("command", command)
                .array("argv", argv)
                .finish();
    }

    public static SessionStartLine decode(String json) {
        return new SessionStartLine(
                Jsonl.longValue(json, "ts", 0), Jsonl.str(json, "command"), Jsonl.strArray(json, "argv"));
    }
}
