// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * The budgeted tail of an app's log: the last lines, or only the lines of one request. A request is
 * the lines that match it, each with the indented lines and {@code Caused by:} lines under it, so a
 * stack trace stays with the line that logged it. {@code /…/} is a Java regex, anything else a
 * substring (a request id, a path, a thread name).
 */
public final class McpAppLog {

    /** Lines a tail answers when the call names none. */
    public static final int DEFAULT_LINES = 60;

    /** The most lines one call answers. */
    public static final int MAX_LINES = 400;

    /** The most characters of one line a tail keeps. */
    static final int LINE_CHARS = 400;

    /** The most characters one tail answers, every line counted. */
    static final int BUDGET_CHARS = 16_000;

    /** How long a regex request may scan one log before the tail answers what it matched so far. */
    static final long REGEX_MILLIS = 2_000;

    /** A line that continues the one above it: the exception it logged, a stack frame, a cause, a wrapped value. */
    private static final Pattern CONTINUATION =
            Pattern.compile("^(?:\\s+\\S|Caused by:|Suppressed:|[\\w.$]+(?:Exception|Error|Throwable)(?::|$))");

    private McpAppLog() {}

    /**
     * What a tail kept: its lines, how many lines the log (or the request's filter) held, and whether
     * a regex ran out of time, so {@code of} counts only the lines before where it stopped.
     */
    public record Tail(List<String> lines, int of, boolean cut) {}

    /** The most bytes of a log one call reads, from its end. */
    static final int READ_BYTES = 8 << 20;

    /**
     * The lines of {@code log}'s last {@link #READ_BYTES}, the first, cut, line dropped; malformed
     * bytes replaced. Empty when the file is not there.
     */
    static List<String> read(Path log) throws IOException {
        if (!Files.isRegularFile(log)) return List.of();
        try (FileChannel in = FileChannel.open(log, StandardOpenOption.READ)) {
            long size = in.size();
            long from = Math.max(0, size - READ_BYTES);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - from));
            int n = 0;
            while (buf.hasRemaining() && n >= 0) n = in.read(buf, from + buf.position());
            List<String> lines = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
            return from == 0 || lines.isEmpty() ? lines : lines.subList(1, lines.size());
        }
    }

    /**
     * The regex a {@code /…/} request spells; null for a substring request.
     *
     * @throws PatternSyntaxException when the text between the slashes is not a Java regex
     */
    static @Nullable Pattern regexOf(String request) {
        String r = request.strip();
        return r.length() > 2 && r.startsWith("/") && r.endsWith("/")
                ? Pattern.compile(r.substring(1, r.length() - 1))
                : null;
    }

    /** {@link #tail(List, String, int, int, long, Clock)} inside {@link #BUDGET_CHARS} and {@link #REGEX_MILLIS}. */
    public static Tail tail(List<String> lines, @Nullable String request, int max) {
        return tail(lines, request, max, BUDGET_CHARS, REGEX_MILLIS, Clock.SYSTEM);
    }

    /**
     * The last {@code max} of {@code lines} that belong to {@code request} (every line when it is
     * null or blank), each cut to {@link #LINE_CHARS}, fewer when they would pass {@code budget}
     * characters. A regex request stops after {@code regexMillis} of {@code clock}'s ticker with what
     * it matched so far.
     *
     * @throws PatternSyntaxException when {@code request} is {@code /…/} around no valid regex
     */
    public static Tail tail(
            List<String> lines, @Nullable String request, int max, int budget, long regexMillis, Clock clock) {
        List<String> picked = lines;
        boolean cut = false;
        if (request != null && !request.isBlank()) {
            Pattern regex = regexOf(request);
            long deadline = clock.nanos() + regexMillis * 1_000_000;
            Predicate<String> match = regex == null
                    ? line -> line.contains(request.strip())
                    : line -> regex.matcher(new Timed(line, clock, deadline)).find();
            picked = new ArrayList<>();
            cut = request(lines, match, picked);
        }
        List<String> kept = new ArrayList<>();
        int chars = 0;
        for (int i = picked.size() - 1; i >= 0 && kept.size() < max; i--) {
            String line = cut(picked.get(i));
            chars += line.length() + 1;
            if (chars > budget && !kept.isEmpty()) break;
            kept.add(line);
        }
        return new Tail(kept.reversed(), picked.size(), cut);
    }

    /**
     * Adds to {@code out} the lines {@code match} takes, each followed by the lines that continue it.
     * Returns true when a regex ran out of time, {@code out} holding what came before.
     */
    static boolean request(List<String> lines, Predicate<String> match, List<String> out) {
        boolean inBlock = false;
        for (String line : lines) {
            boolean hit;
            try {
                hit = match.test(line);
            } catch (OutOfTime e) {
                return true;
            }
            if (hit) {
                out.add(line);
                inBlock = true;
            } else if (inBlock && CONTINUATION.matcher(line).find()) {
                out.add(line);
            } else {
                inBlock = false;
            }
        }
        return false;
    }

    private static String cut(String line) {
        return line.length() <= LINE_CHARS ? line : line.substring(0, LINE_CHARS) + "…";
    }

    /** A regex's input that ends the match once {@code clock}'s {@link Clock#nanos} passes {@code deadline}. */
    private record Timed(CharSequence text, Clock clock, long deadline) implements CharSequence {
        @Override
        public char charAt(int index) {
            if (clock.nanos() - deadline > 0) throw new OutOfTime();
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text.subSequence(start, end), clock, deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** A regex request's time ran out mid-line. */
    private static final class OutOfTime extends RuntimeException {
        OutOfTime() {
            super(null, null, false, false);
        }
    }
}
