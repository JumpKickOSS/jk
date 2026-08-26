// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.CatalogReadAck;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code jk library search <term>...} — substring match against the library catalog. */
public final class LibrarySearchCommand implements CliCommand {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Find library entries by substring of name, group, or artifact";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<N>", "Cap results shown (default: no cap)", "--limit"),
                Opt.flag("Append the source layer to each row.", "--show-layer"),
                Opt.flag("Group results under a heading per source layer.", "--group-by-layer"),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "term",
                Arity.ONE_OR_MORE,
                "One or more substrings; all must match.\nMatched against name, group, or artifact."));
    }

    private boolean showLayer, groupByLayer;

    @Override
    public int run(Invocation in) {
        List<String> terms = in.positionals();
        Integer limit = in.value("limit").map(Integer::parseInt).orElse(null);
        this.showLayer = in.isSet("show-layer");
        this.groupByLayer = in.isSet("group-by-layer");
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        CatalogReadAck ack;
        try {
            ack = EngineClient.catalogRead(
                    EnginePaths.current(),
                    global.workingDir(),
                    cacheDir != null ? cacheDir : JkDirs.cache(),
                    "search",
                    terms,
                    global.offline,
                    true,
                    false);
        } catch (IOException e) {
            CommandWedge.printFail("Library", String.valueOf(e.getMessage()));
            return 1;
        }
        for (String w : ack.warnings()) CliOutput.stderr().println(w);
        if (ack.error() != null) {
            CommandWedge.printFail("Library", ack.error());
            return 1;
        }

        List<CatalogReadAck.Entry> hits = ack.entries();
        if (hits.isEmpty()) {
            CliOutput.out(
                    "No matches" + (global.offline ? " (cached locally)" : "") + " for: " + String.join(" ", terms));
            return 1;
        }
        int total = hits.size();
        int shown = limit != null && limit > 0 && total > limit ? limit : total;
        List<CatalogReadAck.Entry> visible = hits.subList(0, shown);

        if (groupByLayer) {
            renderGrouped(ack.layerNames(), visible);
        } else {
            printTable("Library search", visible);
        }
        if (shown < total)
            CliOutput.out("… and " + (total - shown) + " more (pass --limit " + total + " or refine the search)");
        return 0;
    }

    private void renderGrouped(List<String> layerNames, List<CatalogReadAck.Entry> visible) {
        boolean firstGroup = true;
        for (String layer : layerNames) {
            List<CatalogReadAck.Entry> inLayer =
                    visible.stream().filter(h -> h.layer().equals(layer)).toList();
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) CliOutput.out();
            firstGroup = false;
            printTable("Library search — " + layer, inLayer);
        }
    }

    private void printTable(String title, List<CatalogReadAck.Entry> visible) {
        List<String> headers = new ArrayList<>(List.of("Name", "Coordinates"));
        if (showLayer && !groupByLayer) headers.add("Layer");
        headers.add("Cached");
        List<List<String>> rows = new ArrayList<>();
        for (CatalogReadAck.Entry h : visible) {
            List<String> cells = new ArrayList<>(List.of(h.name(), h.moduleKey()));
            if (showLayer && !groupByLayer) cells.add(h.layer());
            cells.add(String.join(", ", h.cached()));
            rows.add(cells);
        }
        CommandWedge.envelopeStart();
        for (String line : Table.render(title, headers, rows)) {
            CliOutput.out(line);
        }
    }
}
