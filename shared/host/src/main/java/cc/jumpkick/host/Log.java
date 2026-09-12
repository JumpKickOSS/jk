// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;
import org.jspecify.annotations.Nullable;

/**
 * The one logger a jk process writes through: a facade over {@link System.Logger} with the four
 * levels jk uses ({@code debug}, {@code info}, {@code warn}, {@code error}), a {@code key=value}
 * suffix for structured detail, and {@link #install}, which binds the JDK logging backend to one
 * stream, one line format and one redaction. There is no framework underneath — the CLI never
 * calls this class, a worker binds its own stderr, and the engine binds the stream its
 * size-capped log sink owns, so the cap and the redaction cover every line.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    private Log() {}

    /** Lazily bound so a caller that only parses a level name never reaches the logging backend. */
    private static final class Backend {
        static final System.Logger LOGGER = System.getLogger("jk");

        /** The backend logger the level is set on; held so the level is not garbage-collected. */
        static @Nullable Logger JK;
    }

    /** True when a {@link #debug} line would be written; guards detail that is costly to build. */
    public static boolean debugEnabled() {
        return Backend.LOGGER.isLoggable(System.Logger.Level.DEBUG);
    }

    public static void debug(String message, @Nullable Object... detail) {
        log(System.Logger.Level.DEBUG, message, null, detail);
    }

    public static void debug(String message, Throwable cause) {
        log(System.Logger.Level.DEBUG, message, cause);
    }

    public static void info(String message, @Nullable Object... detail) {
        log(System.Logger.Level.INFO, message, null, detail);
    }

    public static void warn(String message, @Nullable Object... detail) {
        log(System.Logger.Level.WARNING, message, null, detail);
    }

    public static void warn(String message, Throwable cause) {
        log(System.Logger.Level.WARNING, message, cause);
    }

    public static void error(String message, @Nullable Object... detail) {
        log(System.Logger.Level.ERROR, message, null, detail);
    }

    public static void error(String message, Throwable cause) {
        log(System.Logger.Level.ERROR, message, cause);
    }

    /**
     * Structured detail as a {@code " key=value key=value"} suffix from alternating keys and
     * values; a value with whitespace is quoted so a line stays splittable on spaces.
     */
    public static String detail(@Nullable Object... pairs) {
        if (pairs.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            String value = i + 1 < pairs.length ? String.valueOf(pairs[i + 1]) : "";
            boolean quote = value.isEmpty() || value.chars().anyMatch(Character::isWhitespace);
            sb.append(' ').append(pairs[i]).append('=');
            if (quote) sb.append('"').append(value.replace("\"", "\\\"")).append('"');
            else sb.append(value);
        }
        return sb.toString();
    }

    /** The level {@code name} spells ({@code debug|info|warn|error}, any case); empty otherwise. */
    public static Optional<System.Logger.Level> level(@Nullable String name) {
        if (name == null) return Optional.empty();
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "debug" -> Optional.of(System.Logger.Level.DEBUG);
            case "info" -> Optional.of(System.Logger.Level.INFO);
            case "warn", "warning" -> Optional.of(System.Logger.Level.WARNING);
            case "error" -> Optional.of(System.Logger.Level.ERROR);
            default -> Optional.empty();
        };
    }

    /** {@link #install(PrintStream, System.Logger.Level, UnaryOperator)} on this process's {@code System.err}. */
    public static void install(System.Logger.Level level, UnaryOperator<String> redact) {
        install(System.err, level, redact);
    }

    /**
     * Bind the JDK logging backend to {@code out}: every record at {@code level} or above on jk's
     * own logger, and every record at INFO or above from any other logger in the process, is
     * written as one {@code HH:mm:ss.SSS LEVEL message} line (a cause follows as its stack), and
     * the whole text passes through {@code redact} first. The level is jk's alone on purpose: at
     * DEBUG the JDK's HTTP server and TLS internals would otherwise drown jk's lines. Replaces
     * whatever handlers the backend had; calling it again re-binds — the engine does so once its
     * log sink has taken over {@code System.err}.
     */
    public static void install(PrintStream out, System.Logger.Level level, UnaryOperator<String> redact) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            h.flush();
            root.removeHandler(h);
        }
        Handler handler = new StreamHandler(out, new LineFormatter(redact)) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        handler.setLevel(Level.ALL);
        try {
            handler.setEncoding("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        Logger jk = Logger.getLogger(Backend.LOGGER.getName());
        jk.setLevel(julLevel(level));
        Backend.JK = jk;
    }

    private static void log(
            System.Logger.Level level, String message, @Nullable Throwable cause, @Nullable Object... detail) {
        if (!Backend.LOGGER.isLoggable(level)) return;
        String line = detail.length == 0 ? message : message + detail(detail);
        if (cause == null) Backend.LOGGER.log(level, line);
        else Backend.LOGGER.log(level, line, cause);
    }

    private static Level julLevel(System.Logger.Level level) {
        return switch (level) {
            case ALL, TRACE -> Level.FINER;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case ERROR -> Level.SEVERE;
            case OFF -> Level.OFF;
        };
    }

    /** {@code HH:mm:ss.SSS LEVEL message\n[stack]}, redacted as one text. */
    private static final class LineFormatter extends Formatter {
        private final UnaryOperator<String> redact;

        LineFormatter(UnaryOperator<String> redact) {
            this.redact = redact;
        }

        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();
            sb.append(TIME.format(LocalTime.ofInstant(record.getInstant(), ZoneId.systemDefault())));
            sb.append(' ').append(name(record.getLevel())).append(' ');
            sb.append(formatMessage(record)).append('\n');
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                StringWriter stack = new StringWriter();
                thrown.printStackTrace(new PrintWriter(stack));
                sb.append(stack);
            }
            return redact.apply(sb.toString());
        }

        private static String name(Level level) {
            int severity = level.intValue();
            if (severity >= Level.SEVERE.intValue()) return "ERROR";
            if (severity >= Level.WARNING.intValue()) return "WARN ";
            if (severity >= Level.INFO.intValue()) return "INFO ";
            return "DEBUG";
        }
    }
}
