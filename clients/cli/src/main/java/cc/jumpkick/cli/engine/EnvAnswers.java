// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.ClientEnvForward;
import cc.jumpkick.host.Os;
import cc.jumpkick.wire.protocol.EnvAskFrame;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * This shell answering a job's question about it ({@link EnvAskFrame}): a test JVM of the job
 * looked up a variable the request did not carry. The answer comes from this process's own
 * environment, with every {@code jk.env.*} override applied ({@link ClientEnvForward#wholeShell}),
 * and is written on the job's connection; nothing else sees it.
 */
final class EnvAnswers {

    private EnvAnswers() {}

    /** The answer line to {@code askLine} from {@code shell}. */
    static String answer(String askLine, Map<String, String> shell) {
        EnvAskFrame ask = EnvAskFrame.decode(askLine);
        if (EnvAskFrame.ALL.equals(ask.what())) return EnvAskFrame.answer(ask.id(), shell, null);
        if (EnvAskFrame.NAMES.equals(ask.what())) {
            return EnvAskFrame.answer(ask.id(), Map.of(), new ArrayList<>(new TreeSet<>(shell.keySet())));
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (String name : ask.names()) {
            String v = lookup(shell, name);
            if (v != null) vars.put(name, v);
        }
        return EnvAskFrame.answer(ask.id(), vars, null);
    }

    /** Answer {@code askLine} on {@code ch} from this process's shell; a channel already closed answers nothing. */
    static void answer(SocketChannel ch, String askLine) {
        byte[] bytes = (answer(askLine, ClientEnvForward.wholeShell()) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        try {
            while (buf.hasRemaining()) ch.write(buf);
        } catch (IOException gone) {
            // The engine reads the connection's end as the client gone, and the suite as unanswered.
        }
    }

    /** {@code name} in {@code shell}; on Windows whatever its case, as the process environment reads it. */
    private static @Nullable String lookup(Map<String, String> shell, String name) {
        String v = shell.get(name);
        if (v != null || !Os.isWindows()) return v;
        for (Map.Entry<String, String> e : shell.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
