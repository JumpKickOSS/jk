// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.model.command.Invocation;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.jspecify.annotations.Nullable;

/**
 * The client half of {@code --debug-jvm}: read the flag, settle the address the JVM will listen
 * on, and say so once on stderr before the launch.
 *
 * <p>The client settles the address rather than reading it back from the JVM's own "Listening for
 * transport" line, for three reasons that hold on every surface at once. {@code jk run} hands the
 * terminal to the program, so nothing can read its stdout without proxying the terminal it was
 * given. A suspended JVM waits for the attach before it prints anything the user can act on, so
 * the address has to be known before the JVM exists — and a BSP client needs it before the
 * result, which only arrives after the run. And one mechanism serves the CLI, BSP and the wire
 * alike: the spelled address rides the request, and the JVM is told to bind exactly it.
 *
 * <p>{@code 0} asks this host for a free ephemeral port, which is bound and released here so the
 * JVM can take it. The window between release and the JVM's bind is real; a collision is loud,
 * not silent — the JVM refuses to start and the run fails with the bind error on its output.
 */
public final class DebugAttach {

    /** The {@code --debug-jvm} option's canonical name in an {@link Invocation}. */
    public static final String OPTION = "debug-jvm";

    private DebugAttach() {}

    /** The flag's value as given, or {@code null} when it was not passed; a bad spec throws. */
    public static @Nullable DebugJvm fromFlag(Invocation in) {
        if (!in.has(OPTION)) return null;
        return DebugJvm.parse(in.value(OPTION).orElse(""));
    }

    /**
     * The address the launched JVM is told to bind: an explicit port as given, a {@code 0} replaced
     * by a free port this host hands out now.
     */
    public static DebugJvm bind(DebugJvm debug) throws IOException {
        if (!debug.portChosenByClient()) return debug;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(bindAddress(debug.host()));
            return debug.withPort(socket.getLocalPort());
        }
    }

    private static InetSocketAddress bindAddress(String host) throws IOException {
        return "*".equals(host) ? new InetSocketAddress(0) : new InetSocketAddress(InetAddress.getByName(host), 0);
    }

    /** The announcement as plain text — what BSP sends as a log message. */
    public static String announcement(DebugJvm debug) {
        return "Debugger listening on " + debug.address() + tail(debug);
    }

    /** The announcement on stderr, in the CLI's chrome: one line, before the JVM starts. */
    public static void announce(DebugJvm debug) {
        CliOutput.err(announcedLine(debug));
    }

    /**
     * The line {@link #announce} prints: a styled glyph, then the plain {@link #announcement}. The
     * address itself is never styled — it is what a person copies into an IDE and what a script
     * (or a test) reads back as {@code host:port}, and an escape sequence glued to the host is not
     * a host on any surface, coloured or piped.
     */
    static String announcedLine(DebugJvm debug) {
        return Theme.colorize(Glyphs.pulse(), Theme.active().focused()) + " " + announcement(debug);
    }

    private static String tail(DebugJvm debug) {
        return debug.suspend() ? " — the JVM waits for a debugger to attach" : "";
    }
}
