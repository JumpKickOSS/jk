// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.GuardCommitMsgAck;
import cc.jumpkick.wire.protocol.GuardExplainAck;
import cc.jumpkick.wire.protocol.GuardFreezeAck;
import cc.jumpkick.wire.protocol.GuardTestAck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk guard} — run every house-rule lane now, cache-aware; non-zero on any red. It is the
 * build with tests skipped and the guard lanes on, so packaging is a cache hit and every guard lane
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
                        + "freeze <rule-id> --reason \"…\" accepts its new violations;\n"
                        + "test proves every fixture-bearing rule bites;\n"
                        + "commit-msg <file> judges a commit message;\n"
                        + "hooks [install] prints or installs the git hooks.\n"
                        + "--output sarif prints target/jk-guards.sarif after the run."));
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(build.options());
        opts.add(Opt.value("<text>", "freeze: why these sites are accepted", "--reason"));
        opts.add(Opt.flag("freeze: drop a removed rule's entries", "--retire"));
        opts.add(Opt.value("<kind>", "explain: a kind's keys and example", "--schema"));
        opts.add(Opt.flag("hooks install: overwrite hooks", "--replace"));
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
        if (!positionals.isEmpty() && "test".equals(positionals.get(0))) {
            return test(dir);
        }
        if (!positionals.isEmpty() && "commit-msg".equals(positionals.get(0))) {
            return commitMsg(dir, positionals);
        }
        if (!positionals.isEmpty() && "hooks".equals(positionals.get(0))) {
            return hooks(in, dir, positionals);
        }
        if (in.isSet("schema") || (!positionals.isEmpty() && "explain".equals(positionals.get(0)))) {
            return explain(in, global, dir, positionals);
        }
        if (!positionals.isEmpty()) {
            CommandWedge.printFail(
                    "Guard",
                    "unknown subcommand `" + positionals.get(0)
                            + "`; jk guard [explain [<id>] [--schema <kind>] | freeze <id> --reason \"…\" [--retire] | test"
                            + " | commit-msg <file> | hooks [install] [--replace]]");
            return Exit.USAGE;
        }
        // Every lane, cache-aware: the build with tests skipped and the guard flag on.
        String output = in.value("output").orElse("").trim();
        boolean sarif = output.equalsIgnoreCase("sarif");
        Invocation.Builder b = Invocation.builder();
        for (Opt opt : build.options()) copy(in, opt, b);
        for (Opt opt : GlobalOptions.globalOpts()) {
            if (sarif && opt.canonicalName().equals("output")) continue; // the build runs in text mode, quietly
            copy(in, opt, b);
        }
        if (sarif) b.flag("quiet", true);
        b.flag("skip-tests", true);
        b.flag("guard", true);
        Invocation run = b.build();
        int exit;
        if (sarif) {
            // stdout carries the document alone; the build's lines are noise to a SARIF consumer
            exit = CliOutput.silenced(() -> {
                try {
                    return build.run(run);
                } catch (Exception e) {
                    CommandWedge.printFail("Guard", e.getMessage());
                    return Exit.SOFTWARE;
                }
            });
        } else {
            exit = build.run(run);
        }
        Path target = dir.resolve(BuildLayout.TARGET);
        if (sarif) {
            // The document the lanes left; printed whole so a pipe gets exactly the file.
            Path file = target.resolve(SARIF_FILE);
            if (Files.isRegularFile(file))
                CliOutput.out(Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
            else CommandWedge.printFail("Guard", "no " + file + " was written: no guard lane ran");
        } else if (global.outputIsJson()) {
            // One `guard` event per violation row of the run, after the build's own events.
            Path rows = target.resolve(JSONL_FILE);
            if (Files.isRegularFile(rows)) {
                for (String line : Files.readAllLines(rows, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) CliOutput.out(JsonlShape.guard(line));
                }
            }
        }
        return exit;
    }

    /** The files the engine writes under {@code target/}; named here so the CLI never links the guard engine. */
    static final String SARIF_FILE = "jk-guards.sarif";

    static final String JSONL_FILE = "jk-guards.jsonl";

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

    /**
     * {@code jk guard test}: compile every fixture directory once per owning module and prove each
     * fixture-bearing rule and guard test bites — {@code Bad} fires, {@code Ok} stays quiet. Non-zero
     * when any rule is not proven or the rules do not load.
     */
    private int test(Path dir) {
        GuardTestAck ack;
        try {
            ack = EngineClient.guardTest(EnginePaths.current(), dir);
        } catch (IOException e) {
            CommandWedge.printFail("Guard", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (ack.error() != null) {
            CommandWedge.printFail("Guard", ack.error());
            return 1;
        }
        CliOutput.out(ack.text().stripTrailing());
        return ack.failures() == 0 ? 0 : 1;
    }

    /**
     * {@code jk guard commit-msg <file>}: the {@code commit-msg} hook's entry point. The message is
     * read here and judged by the engine against the {@code commit} rules; non-zero refuses the
     * commit with each rule's {@code instead}.
     */
    private int commitMsg(Path dir, List<String> positionals) {
        if (positionals.size() < 2) {
            CommandWedge.printFail("Guard", "commit-msg needs the message file: jk guard commit-msg <file>");
            return Exit.USAGE;
        }
        Path file = dir.resolve(positionals.get(1));
        String message;
        try {
            message = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CommandWedge.printFail("Guard", "cannot read the commit message at " + file + ": " + e.getMessage());
            return Exit.USAGE;
        }
        GuardCommitMsgAck ack;
        try {
            ack = EngineClient.guardCommitMsg(EnginePaths.current(), dir, message);
        } catch (IOException e) {
            CommandWedge.printFail("Guard", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (ack.error() != null) {
            CommandWedge.printFail("Guard", ack.error());
            return 1;
        }
        CliOutput.out(ack.text().stripTrailing());
        return ack.failures() == 0 ? 0 : 1;
    }

    /**
     * {@code jk guard hooks} prints the {@code commit-msg} and {@code pre-commit} hooks; {@code hooks
     * install} writes them into the repository's hooks directory and the protected-file list into
     * {@code target/}. An existing hook with other content is kept unless {@code --replace}.
     */
    private int hooks(Invocation in, Path dir, List<String> positionals) {
        if (positionals.size() == 1) {
            CliOutput.out(GuardHooks.render());
            return 0;
        }
        if (!"install".equals(positionals.get(1))) {
            CommandWedge.printFail(
                    "Guard", "hooks takes no argument or `install`: jk guard hooks [install] [--replace]");
            return Exit.USAGE;
        }
        GuardHooks.Installed done;
        try {
            done = GuardHooks.install(dir, in.isSet("replace"));
        } catch (IOException e) {
            CommandWedge.printFail("Guard", e.getMessage());
            return Exit.CONFIG;
        }
        for (String w : done.written())
            CliOutput.out("jk guard: installed " + done.hooksDir().resolve(w));
        for (String r : done.refused()) CliOutput.out("jk guard: kept " + r);
        CliOutput.out("jk guard: protected files listed in " + done.protectedList()
                + " (advisory: a hook is local state; the engine's lanes and CI enforce)");
        return done.refused().isEmpty() ? 0 : 1;
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
        GuardHooks.markFreeze(dir);
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
