// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.CatalogReadAck;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** {@code jk library list} — print every library known to the catalog. */
public final class LibraryListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List every library the catalog resolves";
    }

    @Override
    public List<String> aliases() {
        return List.of("ls");
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<layer>", "Filter to one layer (project|global|bundled)", "--layer"),
                Opt.flag("Append the source layer to each row.", "--show-layer"),
                Opt.flag("Group libraries under a heading per source layer.", "--group-by-layer"));
    }

    private String layerFilter;
    private boolean showLayer;
    private boolean groupByLayer;

    @Override
    public int run(Invocation in) {
        this.layerFilter = in.value("layer").orElse(null);
        this.showLayer = in.isSet("show-layer");
        this.groupByLayer = in.isSet("group-by-layer");
        GlobalOptions global = GlobalOptions.from(in);

        CatalogReadAck ack;
        try {
            ack = EngineClient.catalogRead(
                    EnginePaths.current(), global.workingDir(), null, "list", List.of(), global.offline, false, false);
        } catch (IOException e) {
            CommandWedge.printFail("Library", String.valueOf(e.getMessage()));
            return 1;
        }
        for (String w : ack.warnings()) CliOutput.stderr().println(w);
        if (ack.error() != null) {
            CommandWedge.printFail("Library", ack.error());
            return 1;
        }
        List<CatalogReadAck.Entry> entries = ack.entries();
        if (entries.isEmpty()) {
            CliOutput.out("(no libraries registered)");
            return 0;
        }
        return groupByLayer ? listGrouped(ack.layerNames(), entries) : listFlat(entries);
    }

    private int listFlat(List<CatalogReadAck.Entry> entries) {
        List<List<String>> rows = new ArrayList<>();
        for (CatalogReadAck.Entry e : entries) {
            if (layerFilter != null && !e.layer().equals(layerFilter)) continue;
            rows.add(row(e));
        }
        if (rows.isEmpty() && layerFilter != null) {
            CliOutput.out("(no libraries in layer `" + layerFilter + "`)");
            return 0;
        }
        printTable(layerFilter == null ? "Libraries" : "Libraries — " + layerFilter, rows);
        return 0;
    }

    private int listGrouped(List<String> layerNames, List<CatalogReadAck.Entry> entries) {
        Table table = null;
        int shown = 0;
        for (String layer : layerNames) {
            if (layerFilter != null && !layer.equals(layerFilter)) continue;
            Table section = new Table("Libraries — " + layer)
                    .columns(headers().toArray(String[]::new))
                    .showTitle(true)
                    .showColumns(true);
            int added = 0;
            for (CatalogReadAck.Entry e : entries) {
                if (e.layer().equals(layer)) {
                    section.row(row(e).toArray(String[]::new));
                    added++;
                }
            }
            if (added == 0) continue;
            shown += added;
            if (table == null) table = section;
            else table.append(section, Table.Append.SECTION);
        }
        if (shown == 0 && layerFilter != null) {
            CliOutput.out("(no libraries in layer `" + layerFilter + "`)");
            return 0;
        }
        if (table != null) table.print();
        return 0;
    }

    private List<String> headers() {
        List<String> headers = new ArrayList<>(List.of("Name", "Coordinates"));
        if (showLayer && !groupByLayer) headers.add("Layer");
        return headers;
    }

    private void printTable(String title, List<List<String>> rows) {
        Table table = new Table(title).columns(headers().toArray(String[]::new));
        for (List<String> r : rows) table.row(r.toArray(String[]::new));
        table.print();
    }

    private List<String> row(CatalogReadAck.Entry e) {
        List<String> cells = new ArrayList<>(List.of(e.name(), e.moduleKey()));
        if (showLayer && !groupByLayer) cells.add(e.layer());
        return cells;
    }
}
