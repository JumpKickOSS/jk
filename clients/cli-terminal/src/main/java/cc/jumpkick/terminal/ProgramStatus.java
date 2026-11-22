// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a program tells its terminal it is doing, in either of the two wire forms a terminal may
 * read: the Program Status Protocol, OSC 7501 (<a
 * href="https://www.superlogical.com/rex/docs/build/program-status">spec, rev 0.4</a>), or the
 * older OSC 9;4 taskbar progress. Pure encoding and reply parsing; whether and where to send them
 * is the caller's policy.
 */
public final class ProgramStatus {
    private ProgramStatus() {}

    /** The {@code app} key of every OSC 7501 report: the command id, not the product name. */
    public static final String APP = "jk";

    /** Most bytes of UTF-8 a {@code msg} may decode to; a longer one is cut before encoding. */
    public static final int MSG_MAX_BYTES = 2048;

    /** Most bytes of UTF-8 a {@code title} may decode to; a longer one is cut before encoding. */
    public static final int TITLE_MAX_BYTES = 192;

    /** The feature query, answered by {@code ESC ]7501;?} on a terminal that reads OSC 7501. */
    public static final String QUERY = Ansi.OSC + "7501;?" + Ansi.ST;

    /** Primary device attributes: every terminal answers, so its reply marks the query unanswered. */
    public static final String DEVICE_ATTRIBUTES = Ansi.CSI + "c";

    /** The states of OSC 7501; {@link #CLEAR} removes a record instead of setting one. */
    public enum State {
        IDLE,
        WORKING,
        BLOCKED,
        ERROR,
        DONE,
        CLEAR;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the user must do before a {@link State#BLOCKED} program goes on. */
    public enum Waiting {
        /** Approve something: a yes/no confirmation. */
        PERMISSION,
        /** Type or pick an answer. */
        QUESTION,
        /** Log in, or give a token or credential. */
        AUTH;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One report. {@code progress} is 0–100, or {@code -1} for none; {@code kind} is read only when
     * blocked; a null {@code id} addresses the root record; {@code title} and {@code msg} are plain
     * text, made safe when encoded.
     */
    public record Report(
            State state,
            int progress,
            @Nullable Waiting kind,
            @Nullable String id,
            @Nullable String title,
            @Nullable String msg) {

        public static Report working() {
            return new Report(State.WORKING, -1, null, null, null, null);
        }

        public static Report working(int percent) {
            return new Report(State.WORKING, Math.clamp(percent, 0, 100), null, null, null, null);
        }

        public static Report blocked(Waiting kind) {
            return new Report(State.BLOCKED, -1, kind, null, null, null);
        }

        public static Report error() {
            return new Report(State.ERROR, -1, null, null, null, null);
        }

        public static Report done() {
            return new Report(State.DONE, -1, null, null, null, null);
        }

        public static Report idle() {
            return new Report(State.IDLE, -1, null, null, null, null);
        }

        /** Removes the record {@code id} and every record beneath it. */
        public static Report clear(String id) {
            return new Report(State.CLEAR, -1, null, id, null, null);
        }

        public Report withMsg(@Nullable String text) {
            return new Report(state, progress, kind, id, title, text);
        }

        /** This report for the child record {@code childId} ({@code build}, {@code build/test}), labelled {@code label}. */
        public Report child(String childId, @Nullable String label) {
            return new Report(state, progress, kind, childId, label, msg);
        }
    }

    /** The spec's id grammar: up to 8 segments of 1–32 characters, 128 in all. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.+-]{1,32}(/[A-Za-z0-9_.+-]{1,32}){0,7}");

    private static final int ID_MAX = 128;

    /**
     * {@code report} as OSC 7501, terminated by ST. Every report but a clear carries {@code app=jk};
     * an id outside the spec's grammar is the caller's error, never sent.
     */
    public static String osc7501(Report report) {
        StringBuilder b = new StringBuilder(Ansi.OSC)
                .append("7501;state=")
                .append(report.state().wire());
        String id = report.id();
        if (id != null) {
            if (id.length() > ID_MAX || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("not a program status id: " + id);
            }
            b.append(":id=").append(id);
        }
        if (report.state() == State.CLEAR) return b.append(Ansi.ST).toString();
        b.append(":app=").append(APP);
        boolean measured = report.state() == State.WORKING || report.state() == State.BLOCKED;
        if (measured && report.progress() >= 0) b.append(":progress=").append(report.progress());
        if (report.state() == State.BLOCKED && report.kind() != null) {
            b.append(":kind=").append(report.kind().wire());
        }
        if (report.title() != null) b.append(":title=").append(text(report.title(), TITLE_MAX_BYTES));
        if (report.msg() != null) b.append(":msg=").append(text(report.msg(), MSG_MAX_BYTES));
        return b.append(Ansi.ST).toString();
    }

    /**
     * {@code report} as OSC 9;4, or empty for a child record or a clear: the taskbar has one
     * indicator per terminal, so only the root record maps onto it.
     */
    public static String osc94(Report report) {
        if (report.id() != null) return "";
        return switch (report.state()) {
            case WORKING ->
                report.progress() >= 0 ? Ansi.taskbarProgress(report.progress()) : Ansi.taskbarIndeterminate();
            case BLOCKED -> Ansi.OSC + "9;4;4" + Ansi.BEL;
            case ERROR -> Ansi.OSC + "9;4;2" + Ansi.BEL;
            case DONE, IDLE -> Ansi.taskbarClear();
            case CLEAR -> "";
        };
    }

    /**
     * {@code s} as a {@code msg} or {@code title} value: one line, control characters replaced by
     * spaces, cut to at most {@code maxBytes} of UTF-8 on a character boundary, then base64.
     */
    static String text(String s, int maxBytes) {
        StringBuilder line = new StringBuilder(s.length());
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isControl(cp)) cp = ' ';
            int size = utf8Length(cp);
            if (bytes + size > maxBytes) break;
            line.appendCodePoint(cp);
            bytes += size;
        }
        return Base64.getEncoder().encodeToString(line.toString().strip().getBytes(StandardCharsets.UTF_8));
    }

    /** The characters the spec refuses in decoded text: C0, DEL and C1. */
    static boolean isControl(int cp) {
        return cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F);
    }

    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    /** What the replies to {@link #QUERY} then {@link #DEVICE_ATTRIBUTES} say so far. */
    public enum Answer {
        /** The OSC 7501 reply came before the device attributes. */
        SUPPORTED,
        /** The device attributes came first: the query went unanswered. */
        UNSUPPORTED,
        /** Neither reply has arrived yet. */
        PENDING
    }

    private static final String QUERY_REPLY = Ansi.OSC + "7501;?";

    /** A primary device attributes reply, {@code CSI ? Ps ; … c}. */
    private static final Pattern DEVICE_REPLY = Pattern.compile("\u001b\\[\\?[0-9;]*c");

    /**
     * Read {@code replies}, everything the terminal sent since the probe was written, as Latin-1
     * chars. Whichever reply comes first decides; anything else in between is ignored.
     */
    public static Answer answer(String replies) {
        int query = replies.indexOf(QUERY_REPLY);
        var device = DEVICE_REPLY.matcher(replies);
        int attributes = device.find() ? device.start() : -1;
        if (query >= 0 && (attributes < 0 || query < attributes)) return Answer.SUPPORTED;
        if (attributes >= 0) return Answer.UNSUPPORTED;
        return Answer.PENDING;
    }

    /** Whether {@code replies} hold the device attributes reply, the last one a probe waits for. */
    public static boolean answeredAttributes(String replies) {
        return DEVICE_REPLY.matcher(replies).find();
    }
}
