// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.ProgressRow;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
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
import org.jspecify.annotations.Nullable;

/**
 * One {@code jk graph} operation. A refresh that takes long enough to notice gets a progress row;
 * {@code build} settles on a summary line, every read prints its answer as plain text.
 */
final class GraphOpCommand implements CliCommand {

    /** A refresh shorter than this paints nothing: an unchanged graph answers without a flicker. */
    private static final long QUIET_MILLIS = 250;

    private final String op;
    private final String description;
    private final List<Opt> options;
    private final List<Param> parameters;

    GraphOpCommand(String op, String description, List<Opt> options, List<Param> parameters) {
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
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        GraphRequest request = request(dir, in, global.rebuild);
        Row row = new Row(Clock.SYSTEM.millis());
        GraphAck ack;
        try {
            ack = EngineClient.graph(EnginePaths.current(), request, row::update);
        } finally {
            row.close();
        }
        if (!ack.ok()) {
            CommandWedge.printFail("Graph", ack.error());
            return Exit.FAILURE;
        }
        String note = ack.note();
        if (note != null) CommandWedge.printChipErr(Glyphs.BANG, "Graph", note);
        if (op.equals("build")) {
            CommandWedge.printOk("Graph", summary(ack));
            return 0;
        }
        CliOutput.out(ack.text().stripTrailing());
        return ack.text().startsWith("No ") ? 1 : 0;
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
                in.isSet("all"),
                in.isSet("undirected"),
                number(in, "max-hops", 8),
                number(in, "top", 10),
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
        if (in.isSet("graphify")) out.add("graphify=true");
        if (in.isSet("libraries")) out.add("libraries=true");
        if (in.isSet("transitive")) out.add("transitive=true");
        return out;
    }

    /** Options passed through to the worker by name. */
    static final List<String> SETTINGS = List.of("view", "kind", "visibility", "extends", "implements", "annotated");

    private static int number(Invocation in, String option, int fallback) {
        String raw = in.value(option).orElse(null);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " takes a number, got '" + raw + "'");
        }
    }

    /** {@code 4,812 nodes · 11,903 edges · 37 communities from 1,204 files (6 read) → target/graph}. */
    static String summary(GraphAck ack) {
        String read = ack.extracted() == 0 ? "none changed" : String.format(Locale.ROOT, "%,d read", ack.extracted());
        String classes = ack.classes() == 0 ? "" : String.format(Locale.ROOT, ", %,d classes", ack.classes());
        return String.format(
                        Locale.ROOT,
                        "%,d nodes · %,d edges · %,d communities from %,d files (%s%s)",
                        ack.nodes(),
                        ack.edges(),
                        ack.communities(),
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
