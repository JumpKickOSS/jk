// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;

/**
 * A failed test's snapshot as the agent report quotes it: one line per frame, the frame as a stack
 * trace names it, then its arguments and locals as {@code name=value}. The test runner sends it as
 * the {@code snapshot} of the failure's {@code throwable}, already inside its budget; this holds the
 * report to {@link #MAX_FRAMES} lines of {@link #MAX_LINE_CHARS} whatever a runner sends.
 */
public final class FailureSnapshotLines {

    /** Frames one failure's snapshot shows: the throwing frame and the project frames above it. */
    public static final int MAX_FRAMES = 4;

    /** Characters of one frame's line. */
    public static final int MAX_LINE_CHARS = 512;

    private FailureSnapshotLines() {}

    /** The lines of the {@code snapshot} in {@code throwableJson}; empty when it carries none. */
    public static List<String> of(String throwableJson) {
        List<String> out = new ArrayList<>();
        for (String frame : Jsonl.objectArray(throwableJson, "snapshot")) {
            if (out.size() == MAX_FRAMES) break;
            String at = Jsonl.str(frame, "at");
            if (at == null || at.isBlank()) continue;
            String vars = String.join(", ", Jsonl.strArray(frame, "vars"));
            String line = vars.isEmpty() ? at : at + " " + vars;
            if (line.length() > MAX_LINE_CHARS) {
                int cut = MAX_LINE_CHARS;
                if (Character.isHighSurrogate(line.charAt(cut - 1))) cut--;
                line = line.substring(0, cut) + FailureClip.VALUE_TRUNCATION_MARKER + (line.length() - cut) + " chars)";
            }
            out.add(line.replace("\r", "").replace("\n", " "));
        }
        return List.copyOf(out);
    }
}
