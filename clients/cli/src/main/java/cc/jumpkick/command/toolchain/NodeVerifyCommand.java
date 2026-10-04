// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk node verify} — check each of jk's Node.js installs: its {@code node} is there and
 * reports the version its directory names.
 */
public final class NodeVerifyCommand implements CliCommand {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Check that jk's Node.js installs are intact";
    }

    @Override
    public int run(Invocation in) throws Exception {
        boolean json = GlobalOptions.outputIsJson(in);
        List<NodeInstalls.Install> managed = NodeCli.installs().managed();
        if (managed.isEmpty()) {
            if (!json) CommandWedge.printOk(NodeCli.WEDGE, "No Node.js installed by jk to verify.");
            return 0;
        }
        boolean failed = false;
        if (!json) CommandWedge.envelopeStart();
        for (NodeInstalls.Install i : managed) {
            String problem = check(new NodeHome(i.home(), i.version(), i.source(), null));
            failed |= problem != null;
            if (json) {
                NodeCli.row(EngineProtocol.NODE_VERIFY, f -> f.string("version", i.version())
                        .string("home", i.home().toString())
                        .bool("ok", problem == null)
                        .optionalString("detail", problem));
            } else {
                CliOutput.out((problem == null ? "ok      " : "broken  ")
                        + i.version()
                        + (problem == null ? "" : " — " + problem));
            }
        }
        if (json) return failed ? 1 : 0;
        if (failed) {
            CommandWedge.printFail(
                    NodeCli.WEDGE,
                    "a Node.js install is broken — `jk node uninstall <version>`, then install it again");
            return 1;
        }
        CommandWedge.printOk(
                NodeCli.WEDGE,
                managed.size() == 1 ? "1 Node.js install verified." : managed.size() + " Node.js installs verified.");
        return 0;
    }

    /** What is wrong with {@code home}, or null when its {@code node} runs and reports its version. */
    static @Nullable String check(NodeHome home) throws IOException, InterruptedException {
        Path node = home.node();
        if (!Files.isRegularFile(node)) return "no " + node.getFileName() + " under " + home.home();
        if (!PathUtil.isRunnable(node)) return node + " is not executable";
        Process p = new ProcessBuilder(node.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return node + " --version did not answer";
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String reported = out.startsWith("v") ? out.substring(1) : out;
        if (p.exitValue() != 0) return node + " --version exited " + p.exitValue();
        return reported.equals(home.version()) ? null : "reports " + out + ", not " + home.version();
    }
}
