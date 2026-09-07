// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

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

    /** The one-line progress text for a chunk of output: stripped, capped at 100 chars. */
    static String trimLine(String text) {
        String t = text.strip();
        return t.length() > 100 ? t.substring(0, 97) + "…" : t;
    }
}
