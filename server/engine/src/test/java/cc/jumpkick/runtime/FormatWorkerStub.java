// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
 * is: for each {@code RUN} it emits one {@code file} event per status/path pair, then answers
 * {@code exitCode} when that is the worker's own {@code 0} or {@code 1}, and dies with it otherwise.
 * Emitting fewer pairs than the run's file total is a worker that stopped early.
 */
public final class FormatWorkerStub {

    private FormatWorkerStub() {}

    public static void main(String[] args) throws IOException {
        PrintStream out =
                new PrintStream(new FileOutputStream(FileDescriptor.out), /* autoFlush */ true, StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, "##JKFMT:");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int exit = Integer.parseInt(args[0]);
        while (true) {
            writer.emit(PluginReply.ready());
            String line = in.readLine();
            if (line == null || line.equals("DONE")) return;
            for (int i = 1; i + 1 < args.length; i += 2) {
                writer.emit(PluginReply.file(args[i + 1], args[i], null));
            }
            out.flush();
            if (exit != 0 && exit != 1) System.exit(exit);
            writer.emit(PluginReply.exited(exit, false));
        }
    }
}
