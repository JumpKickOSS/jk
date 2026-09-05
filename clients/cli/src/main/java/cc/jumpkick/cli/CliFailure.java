// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.util.JkDirs;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The last catch in the CLI. A throwable no verb handled reaches the user as one Error wedge
 * carrying its message, reaches {@code $JK_STATE_DIR/cli.log} with the full stack, and exits {@link
 * Exit#SOFTWARE}; under {@code --output json} the wedge is one machine line instead, and {@code -v}
 * adds the stack to the human stream. The resident engine keeps its own log for daemon internals;
 * this is the slim client's durable record of its own failures, so a stack that scrolled off a
 * terminal is still somewhere.
 */
final class CliFailure {

    /** Under the state root; appended to, never rotated — the entries are rare by construction. */
    static final String LOG_NAME = "cli.log";

    static final String CWD_GONE = "Could not determine current working directory.";

    private CliFailure() {}

    /**
     * The process's working directory no longer exists: {@code getcwd} fails once the inode is gone,
     * and the native image reports it as an {@code Error} from properties initialisation. Not a jk
     * defect and not something a stack helps with, so it is a Warning, not the Error path.
     */
    static final class WorkingDirectoryGone extends RuntimeException {
        WorkingDirectoryGone(Throwable cause) {
            super(CWD_GONE, cause);
        }
    }

    /** True for the native image's report of a vanished working directory. */
    static boolean isWorkingDirectoryGone(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("current working directory")) return true;
        }
        return false;
    }

    /**
     * One Warning line on the human stream (or one machine line), and a usage exit. Written with
     * {@link System#err} and the plain glyph on purpose: the session and the theme both resolve the
     * working directory when they initialise, so with it gone the wedge renderer is the next thing
     * to fail, and a warning that throws is a stack on the terminal again.
     */
    static int workingDirectoryGone(String[] args) {
        if (wantsJson(args)) {
            System.out.println(machineLine("warning", CWD_GONE, null, Exit.USAGE));
        } else {
            System.err.println(Glyphs.BANG_PLAIN + " jk   " + CWD_GONE);
        }
        return Exit.USAGE;
    }

    /** The Error wedge or machine line, the log entry, and {@link Exit#SOFTWARE}. */
    static int unhandled(Throwable t, String[] args) {
        String message = messageOf(t);
        appendLog(args, t);
        if (wantsJson(args)) {
            System.out.println(machineLine("error", message, t.getClass().getName(), Exit.SOFTWARE));
        } else {
            String line;
            try {
                line = CommandWedge.fail("Error", message);
            } catch (Throwable rendering) {
                // The theme could not come up either; the message still has to.
                line = Glyphs.CROSS_PLAIN + " Error   " + message;
            }
            System.err.println(line);
            if (wantsVerbose(args)) t.printStackTrace(System.err);
        }
        return Exit.SOFTWARE;
    }

    /** The throwable's message, else its class — a wedge with an empty message says nothing. */
    static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.strip();
    }

    private static String machineLine(String type, String message, @Nullable String exceptionClass, int exit) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", type);
        fields.put("message", message);
        if (exceptionClass != null) fields.put("exceptionClass", exceptionClass);
        return Jsonl.append(Jsonl.map(fields), "\"exit\":" + exit);
    }

    /**
     * Best-effort: a state root that cannot be resolved or written must not hide the failure the
     * user is about to read. The entry is the invocation, then the stack, so the file reads as a
     * journal.
     */
    static void appendLog(String[] args, Throwable t) {
        try {
            Path log = JkDirs.state().resolve(LOG_NAME);
            Files.createDirectories(log.getParent());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println(Instant.now() + "  jk " + String.join(" ", args));
            t.printStackTrace(pw);
            pw.println();
            pw.flush();
            Files.writeString(
                    log, sw.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // The failure the user sees is the one that matters; the log is the durable copy.
        }
    }

    /**
     * Read off the raw argv, because the parsed options may not exist yet — the throwable may have
     * come from dispatch itself. {@code -O json}, {@code --output json}, {@code --output=json} or
     * the {@code JK_OUTPUT} environment variable, exactly as the parsed option resolves them.
     */
    static boolean wantsJson(String[] args) {
        String value = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("-O".equals(a) || "--output".equals(a)) && i + 1 < args.length) value = args[++i];
            else if (a.startsWith("--output=")) value = a.substring("--output=".length());
        }
        return GlobalOptions.outputIsJson(value);
    }

    static boolean wantsVerbose(String[] args) {
        for (String a : args) {
            if ("-v".equals(a) || "--verbose".equals(a)) return true;
        }
        return false;
    }
}
