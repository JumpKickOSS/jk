// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import org.jetbrains.annotations.Nullable;

/**
 * The platform-free half of {@link JkCliRunner}: which stream a chunk belongs to, how a
 * transcript tail is kept, how a status line is trimmed. Plain strings in, plain strings out, so
 * the rules are testable without an IDE.
 */
final class JkCliLines {

    /** Chars of transcript retained per stream on the streaming path (a tail — errors print last). */
    static final int STREAM_RETAIN_CHARS = 64 * 1024;

    private JkCliLines() {}

    /** ProcessOutputTypes.STDERR vs STDOUT — compared by their string form for API stability. */
    static boolean isStderr(String outputType) {
        return "stderr".equalsIgnoreCase(outputType) || outputType.contains("STDERR");
    }

    /** Append to a transcript buffer, trimming to the retained tail on the streaming path. */
    static void append(StringBuilder sb, String text, boolean capped) {
        sb.append(text);
        if (capped && sb.length() > STREAM_RETAIN_CHARS * 2) {
            sb.delete(0, sb.length() - STREAM_RETAIN_CHARS);
        }
    }

    /**
     * The line a failed run is reported by: the first non-blank stderr line, else the first
     * non-blank stdout line that is not JSON, else {@code fallback}. Errors print first on jk's
     * streams; the tail is a stack of consequences.
     */
    static String firstErrorLine(@Nullable String stderr, @Nullable String stdout, String fallback) {
        for (String stream : new String[] {stderr, stdout}) {
            if (stream == null) continue;
            for (String line : stream.split("\n")) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("{")) return t;
            }
        }
        return fallback;
    }

    /** The one-line progress text for a chunk of output: stripped, capped at 100 chars. */
    static String trimLine(String text) {
        String t = text.strip();
        return t.length() > 100 ? t.substring(0, 97) + "…" : t;
    }
}
