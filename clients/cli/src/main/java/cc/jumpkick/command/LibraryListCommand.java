// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

        LibraryCatalog catalog =
                LibraryCatalog.forProject(GlobalOptions.from(in).workingDir(), CliOutput.stderr()::println);
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
        Table table = null;
        int shown = 0;
        for (String layer : catalog.layerNames()) {
            if (layerFilter != null && !layer.equals(layerFilter)) continue;
            Table section = new Table("Libraries — " + layer)
                    .columns(headers().toArray(String[]::new))
                    .showTitle(true)
                    .showColumns(true);
            int added = 0;
            for (String name : names) {
                if (catalog.source(name).orElseThrow().layer().equals(layer)) {
                    var src = catalog.source(name).orElseThrow();
                    section.row(row(name, src).toArray(String[]::new));
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

    private List<String> row(String name, LibraryCatalog.Source src) {
        List<String> cells = new ArrayList<>(List.of(name, src.module().moduleKey()));
        if (showLayer && !groupByLayer) cells.add(src.layer());
        return cells;
    }
}
