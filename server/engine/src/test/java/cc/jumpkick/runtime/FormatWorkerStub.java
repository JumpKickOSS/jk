// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
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
 * <p>Usage: {@code FormatWorkerStub <exitCode> [<status> <path>]...} — emits one {@code file} event
 * per status/path pair, then exits with {@code exitCode}. Emitting fewer pairs than the run's file
 * total is a worker that stopped early.
 */
public final class FormatWorkerStub {

    private FormatWorkerStub() {}

    public static void main(String[] args) {
        PrintStream out =
                new PrintStream(new FileOutputStream(FileDescriptor.out), /* autoFlush */ true, StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, "##JKFMT:");
        int exit = Integer.parseInt(args[0]);
        for (int i = 1; i + 1 < args.length; i += 2) {
            writer.emit(PluginReply.file(args[i + 1], args[i], null));
        }
        out.flush();
        System.exit(exit);
    }
}
