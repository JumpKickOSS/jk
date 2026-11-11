// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.process.CommandChannel;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the forked {@code jk-formatter} worker, used by {@link
 * FormatWorkerCompletenessTest}. It speaks the real {@code ##JKFMT:} protocol through the real
 * {@link ProtocolWriter}/{@link PluginReply} pair, so the host side under test — {@code
 * FormatWorker.runWorker} — is driven exactly as production drives it.
 *
 * <p>What the real worker cannot be made to do on demand is <em>die</em>: the crash this exists to
 * reproduce is a HotSpot SIGSEGV under a full GC. So the stub takes the death as an argument.
 *
 * <p>Usage: {@code FormatWorkerStub <exitCode> [<status> <path>]...} — served, as the real worker
 * is, its commands read from the channel the trailing {@code --commands} names: for
 * each {@code RUN} it emits one {@code file} event per status/path pair, then answers
 * {@code exitCode} when that is the worker's own {@code 0} or {@code 1}, and dies with it otherwise.
 * Emitting fewer pairs than the run's file total is a worker that stopped early.
 */
public final class FormatWorkerStub {

    private FormatWorkerStub() {}

    public static void main(String[] args) throws IOException {
        PrintStream out =
                new PrintStream(new FileOutputStream(FileDescriptor.out), /* autoFlush */ true, StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, "##JKFMT:");
        BufferedReader in = CommandChannel.connect(List.of(args));
        List<String> own = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith(CommandChannel.COMMANDS)) own.add(a);
        }
        int exit = Integer.parseInt(own.get(0));
        while (true) {
            writer.emit(PluginReply.ready());
            String line = in.readLine();
            if (line == null || line.equals("DONE")) return;
            for (int i = 1; i + 1 < own.size(); i += 2) {
                writer.emit(PluginReply.file(own.get(i + 1), own.get(i), null));
            }
            out.flush();
            if (exit != 0 && exit != 1) System.exit(exit);
            writer.emit(PluginReply.exited(exit, false));
        }
    }
}
