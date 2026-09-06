// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.GuardExplainAck;
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
 * entries. {@code jk guard explain [<id>]} prints a rule's card or the catalog and {@code --schema
 * <kind>} a kind's keys with one example. There is no {@code list} and no bare {@code freeze}.
 */
public final class GuardCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public String description() {
        return "Run the house-rule guards; explain or freeze a rule";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "subcommand",
                Arity.ZERO_OR_MORE,
                "explain [<rule-id>] prints a rule's card or the catalog;\n"
                        + "freeze <rule-id> --reason \"…\" accepts its new violations."));
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(build.options());
        opts.add(Opt.value("<text>", "freeze: why these sites are accepted", "--reason"));
        opts.add(Opt.flag("freeze: drop a removed rule's entries", "--retire"));
        opts.add(Opt.value("<kind>", "explain: a kind's keys and example", "--schema"));
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
        if (in.isSet("schema") || (!positionals.isEmpty() && "explain".equals(positionals.get(0)))) {
            return explain(in, global, dir, positionals);
        }
        if (!positionals.isEmpty()) {
            CommandWedge.printFail(
                    "Guard",
                    "unknown subcommand `" + positionals.get(0)
                            + "`; jk guard [explain [<id>] [--schema <kind>] | freeze <id> --reason \"…\" [--retire]]");
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

    /**
     * {@code jk guard explain [<id>] [--schema <kind>]}: the rule card (why, instead, population,
     * baseline count, last outcome, source), the catalog, or a kind's keys and one example. An
     * inline engine read; never plans a build.
     */
    private int explain(Invocation in, GlobalOptions global, Path dir, List<String> positionals) {
        String schema = in.value("schema").orElse(null);
        String id = positionals.size() >= 2 ? positionals.get(1) : null;
        if (schema != null && id != null) {
            CommandWedge.printFail("Guard", "explain takes a rule id or --schema <kind>, not both");
            return Exit.USAGE;
        }
        GuardExplainAck ack;
        try {
            ack = EngineClient.guardExplain(EnginePaths.current(), dir, id, schema);
        } catch (IOException e) {
            CommandWedge.printFail("Guard", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (ack.error() != null) {
            CommandWedge.printFail("Guard", ack.error());
            return 1;
        }
        CliOutput.out(global.outputIsJson() ? ack.json() : ack.text().stripTrailing());
        return 0;
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
