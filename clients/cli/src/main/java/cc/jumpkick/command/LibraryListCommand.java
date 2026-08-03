// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** {@code jk library list} — print every library known to the layered catalog. */
public final class LibraryListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List every library the layered catalog resolves";
    }

    @Override
    public List<String> aliases() {
        return List.of("ls");
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<layer>", "Filter to one layer (project|local|global|bundled)", "--layer"),
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

        LibraryCatalog catalog = LibraryCatalog.layered(CliOutput.stderr()::println);
        Set<String> names = catalog.names();
        if (names.isEmpty()) {
            CliOutput.out("(no libraries registered)");
            return 0;
        }
        return groupByLayer ? listGrouped(catalog, names) : listFlat(catalog, names);
    }

    private int listFlat(LibraryCatalog catalog, Set<String> names) {
        List<List<String>> rows = new ArrayList<>();
        for (String name : names) {
            var src = catalog.source(name).orElseThrow();
            if (layerFilter != null && !src.layer().equals(layerFilter)) continue;
            rows.add(row(name, src));
        }
        if (rows.isEmpty() && layerFilter != null) {
            CliOutput.out("(no libraries in layer `" + layerFilter + "`)");
            return 0;
        }
        printTable(layerFilter == null ? "Libraries" : "Libraries — " + layerFilter, rows);
        return 0;
    }

    private int listGrouped(LibraryCatalog catalog, Set<String> names) {
        boolean firstGroup = true;
        int shown = 0;
        for (String layer : catalog.layerNames()) {
            if (layerFilter != null && !layer.equals(layerFilter)) continue;
            List<List<String>> rows = new ArrayList<>();
            for (String name : names) {
                if (catalog.source(name).orElseThrow().layer().equals(layer)) {
                    rows.add(row(name, catalog.source(name).orElseThrow()));
                }
            }
            if (rows.isEmpty()) continue;
            if (!firstGroup) CliOutput.out();
            firstGroup = false;
            shown += rows.size();
            printTable("Libraries — " + layer, rows);
        }
        if (shown == 0 && layerFilter != null) CliOutput.out("(no libraries in layer `" + layerFilter + "`)");
        return 0;
    }

    private void printTable(String title, List<List<String>> rows) {
        List<String> headers = new ArrayList<>(List.of("Name", "Coordinates"));
        if (showLayer && !groupByLayer) headers.add("Layer");
        cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
        for (String line : cc.jumpkick.cli.tui.BoxTable.render(title, headers, rows)) {
            CliOutput.out(line);
        }
    }

    private List<String> row(String name, LibraryCatalog.Source src) {
        List<String> cells = new ArrayList<>(List.of(name, src.module().moduleKey()));
        if (showLayer && !groupByLayer) cells.add(src.layer());
        return cells;
    }
}
