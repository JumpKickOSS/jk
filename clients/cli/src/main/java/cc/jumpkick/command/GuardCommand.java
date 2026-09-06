// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.GuardFreezeAck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk guard} — run every house-rule lane now, cache-aware; non-zero on any red. It is the
 * build with tests skipped and the gate lanes on, so packaging is a cache hit and every guard lane
 * — model, module, workspace, tree, output — runs and reports. {@code jk guard freeze <id> --reason}
 * accepts a rule's current new violations into the baseline; {@code --retire} drops a removed rule's
 * entries. There is no {@code list} and no bare {@code freeze}.
 */
public final class GuardCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public String description() {
        return "Run the house-rule guards; freeze accepts violations";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(build.options());
        opts.add(Opt.value("<text>", "freeze: why these sites are accepted", "--reason"));
        opts.add(Opt.flag("freeze: drop a removed rule's entries", "--retire"));
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        if (!Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) {
            CommandWedge.printFail("Guard", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        List<String> positionals = in.positionals();
        if (!positionals.isEmpty() && "freeze".equals(positionals.get(0))) {
            return freeze(in, dir, positionals);
        }
        if (!positionals.isEmpty()) {
            CommandWedge.printFail(
                    "Guard",
                    "unknown subcommand `" + positionals.get(0)
                            + "`; jk guard [freeze <id> --reason \"…\" [--retire]]");
            return Exit.USAGE;
        }
        // Every lane, cache-aware: the build with tests skipped and the gate on.
        Invocation.Builder b = Invocation.builder();
        for (Opt opt : build.options()) copy(in, opt, b);
        for (Opt opt : GlobalOptions.globalOpts()) copy(in, opt, b);
        b.flag("skip-tests", true);
        b.flag("gate", true);
        return build.run(b.build());
    }

    private static void copy(Invocation in, Opt opt, Invocation.Builder b) {
        String name = opt.canonicalName();
        if (opt.takesValue()) {
            for (String v : in.values(name)) b.addValue(name, v);
        } else if (in.isSet(name)) {
            b.flag(name, true);
        }
    }

    private int freeze(Invocation in, Path dir, List<String> positionals) throws IOException {
        boolean retire = in.isSet("retire");
        String reason = in.value("reason").orElse(null);
        if (positionals.size() < 2) {
            CommandWedge.printFail("Guard", "freeze needs a rule id: jk guard freeze <id> --reason \"…\"");
            return Exit.USAGE;
        }
        String id = positionals.get(1);
        if (!retire && (reason == null || reason.isBlank())) {
            CommandWedge.printFail(
                    "Guard",
                    "freeze needs --reason: an entry without a reason is a suppression, and there is no suppression syntax");
            return Exit.USAGE;
        }
        GuardFreezeAck ack;
        try {
            ack = EngineClient.guardFreeze(EnginePaths.current(), dir, id, reason, retire);
        } catch (IOException e) {
            CommandWedge.printFail("Guard", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (ack.error() != null) {
            CommandWedge.printFail("Guard", ack.error());
            return 1;
        }
        if (retire) {
            CliOutput.out("jk guard: retired " + id + " — dropped " + ack.accepted() + " baseline "
                    + (ack.accepted() == 1 ? "entry" : "entries") + "; " + ack.total() + " remain");
        } else if (ack.accepted() == 0) {
            CliOutput.out("jk guard: " + id + " has no new violations to freeze");
        } else {
            CliOutput.out("jk guard: froze " + ack.accepted() + " " + (ack.accepted() == 1 ? "violation" : "violations")
                    + " of " + id + " into jk-guards-baseline.toml (" + ack.total()
                    + " entries) — commit the baseline with the reason");
        }
        return 0;
    }
}
