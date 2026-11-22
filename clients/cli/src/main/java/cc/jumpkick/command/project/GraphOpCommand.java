// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.ProgressRow;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One {@code jk graph} or {@code jk code} operation, the engine operation of the same name. A refresh
 * that takes long enough to notice gets a progress row; {@code build} settles on a summary line,
 * every other operation prints its answer as plain text. Under {@code -O jsonl} each prints one row
 * named for its group instead.
 */
final class GraphOpCommand implements CliCommand {

    /** A refresh shorter than this paints nothing: an unchanged graph answers without a flicker. */
    private static final long QUIET_MILLIS = 250;

    static final Opt NO_REFRESH = Opt.flag("Answer from the last graph; skip the refresh.", "-n", "--no-refresh");
    static final Opt BUDGET = Opt.value("<tokens>", "Answer size in tokens (default 2000).", "-b", "--budget");

    /** The options that narrow a read to part of the workspace. */
    static final List<Opt> SCOPE = List.of(
            Opt.value("<globs>", "Only these paths; ! excludes.", "-g", "--scope"),
            CommonOpts.modules("Only these modules (globs; repeatable)."),
            Opt.value("<main|test>", "Only main or test sources.", "-S", "--source-set"));

    /** {@code options} followed by {@link #SCOPE}. */
    static List<Opt> scoped(Opt... options) {
        List<Opt> out = new ArrayList<>(List.of(options));
        out.addAll(SCOPE);
        return List.copyOf(out);
    }

    /** An answer that says nothing was found or nothing was written: the command exits 1. */
    private static final Pattern NOTHING =
            Pattern.compile("^(No |Not (renamed|moved|deleted|pulled up|extracted)\\b)|\\nRolled back");

    private final String group;
    private final String op;
    private final String description;
    private final List<Opt> options;
    private final List<Param> parameters;

    /** Operation {@code op} of {@code jk <group>}. */
    GraphOpCommand(String group, String op, String description, List<Opt> options, List<Param> parameters) {
        this.group = group;
        this.op = op;
        this.description = description;
        this.options = options;
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return op;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<Opt> options() {
        return options;
    }

    @Override
    public List<Param> parameters() {
        return parameters;
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        GraphRequest request = request(dir, in, global.rebuild);
        boolean json = global.outputIsJson();
        Row row = new Row(json ? Long.MAX_VALUE : Clock.SYSTEM.millis());
        GraphAck ack;
        try {
            ack = EngineClient.graph(EnginePaths.current(), request, row::update);
        } finally {
            row.close();
        }
        String label = label(group);
        if (!ack.ok()) {
            CommandWedge.printFail(label, ack.error());
            return Exit.FAILURE;
        }
        String note = ack.note();
        if (note != null) CommandWedge.printChipErr(Glyphs.BANG, label, note);
        boolean found = !NOTHING.matcher(ack.text()).find();
        if (json) {
            JsonlShape.row(group, f -> {
                f.string("op", op);
                if (op.equals("build")) {
                    f.number("nodes", ack.nodes())
                            .number("edges", ack.edges())
                            .number("files", ack.files())
                            .number("read", ack.extracted())
                            .number("classes", ack.classes())
                            .string("dir", ack.dir())
                            .number("millis", ack.millis());
                } else {
                    f.bool("found", found).string("text", ack.text().stripTrailing());
                }
            });
            return op.equals("build") || found ? 0 : 1;
        }
        if (op.equals("build")) {
            CommandWedge.printOk("Graph", summary(ack));
            return 0;
        }
        CliOutput.out(ack.text().stripTrailing());
        return found ? 0 : 1;
    }

    GraphRequest request(Path dir, Invocation in, boolean rebuild) {
        return new GraphRequest(
                dir.toString(),
                op,
                in.positionals(),
                in.isSet("dfs") ? "dfs" : "bfs",
                number(in, "depth", 3),
                number(in, "budget", 2000),
                in.values("context"),
                in.isSet("undirected"),
                number(in, "max-hops", 8),
                in.value("relation").orElse(null),
                rebuild,
                in.isSet("no-refresh"),
                in.value("scope").orElse(null),
                CommonOpts.modulesSpec(in),
                in.value("source-set").orElse(null),
                options(in));
    }

    /** The operation's named settings as {@code key=value}: whichever of {@link #SETTINGS} are set. */
    private List<String> options(Invocation in) {
        List<String> out = new ArrayList<>();
        for (String name : SETTINGS) {
            for (String v : in.values(name)) out.add(name + "=" + v);
        }
        if (in.isSet("transitive")) out.add("transitive=true");
        if (in.isSet("no-accessors")) out.add("accessors=false");
        return out;
    }

    /** Options passed through to the worker by name. */
    static final List<String> SETTINGS = List.of(
            "view", "kind", "visibility", "extends", "implements", "annotated", "also", "module", "library", "via");

    /** {@code code} → {@code Code}: how a group names itself on a status line. */
    static String label(String group) {
        return Character.toUpperCase(group.charAt(0)) + group.substring(1);
    }

    private static int number(Invocation in, String option, int fallback) {
        String raw = in.value(option).orElse(null);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " takes a number, got '" + raw + "'");
        }
    }

    /** {@code 4,812 nodes · 11,903 edges from 1,204 files (6 read) → target/graph}. */
    static String summary(GraphAck ack) {
        String read = ack.extracted() == 0 ? "none changed" : String.format(Locale.ROOT, "%,d read", ack.extracted());
        String classes = ack.classes() == 0 ? "" : String.format(Locale.ROOT, ", %,d classes", ack.classes());
        return String.format(
                        Locale.ROOT,
                        "%,d nodes · %,d edges from %,d files (%s%s)",
                        ack.nodes(),
                        ack.edges(),
                        ack.files(),
                        read,
                        classes)
                + Theme.colorize(" → " + ack.dir(), Theme.active().darkGray())
                + Theme.colorize(
                        String.format(Locale.ROOT, " in %.1fs", ack.millis() / 1000.0),
                        Theme.active().darkGray());
    }

    /** The progress row, opened only once a refresh has run long enough to be worth showing. */
    private static final class Row {
        private final long started;
        private @Nullable ProgressRow row;

        Row(long started) {
            this.started = started;
        }

        synchronized void update(GraphProgress p) {
            if (row == null) {
                if (Clock.SYSTEM.millis() - started < QUIET_MILLIS) return;
                row = ProgressRow.of(CliOutput.stdout(), "Graph")
                        .status(p.label())
                        .cancelSubject("graph")
                        .open();
            }
            row.status(p.label());
            row.update(p.done(), p.total());
        }

        synchronized void close() {
            if (row != null) row.finish();
        }
    }
}
