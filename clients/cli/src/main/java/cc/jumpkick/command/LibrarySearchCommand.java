// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                                "Override the download/action cache (CAS). Default: $JK_CACHE_DIR or $JK_HOME/cache (~/.cache/jk).",
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
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        LibraryCatalog catalog = LibraryCatalog.layered(CliOutput.stderr()::println);
        Path cacheRoot = cacheDir != null ? cacheDir : JkDirs.cache();
        List<String> lowerTerms =
                terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();

        List<Hit> hits = new ArrayList<>();
        for (String name : catalog.names()) {
            var src = catalog.source(name).orElseThrow();
            if (!allMatch(
                    lowerTerms,
                    name.toLowerCase(Locale.ROOT),
                    src.module().group().toLowerCase(Locale.ROOT),
                    src.module().artifact().toLowerCase(Locale.ROOT))) continue;
            List<String> cached = new ArrayList<>(RepoArtifactStore.allVersions(
                    cacheRoot, src.module().group(), src.module().artifact()));
            cached.sort((a, b) -> Versions.compare(b, a));
            if (global.offline && cached.isEmpty()) continue;
            hits.add(new Hit(name, src, cached));
        }

        if (hits.isEmpty()) {
            CliOutput.out(
                    "No matches" + (global.offline ? " (cached locally)" : "") + " for: " + String.join(" ", terms));
            return 1;
        }
        int total = hits.size();
        int shown = limit != null && limit > 0 && total > limit ? limit : total;
        List<Hit> visible = hits.subList(0, shown);

        if (groupByLayer) {
            renderGrouped(catalog, visible);
        } else {
            printTable("Library search", visible);
        }
        if (shown < total)
            CliOutput.out("… and " + (total - shown) + " more (pass --limit " + total + " or refine the search)");
        return 0;
    }

    private void renderGrouped(LibraryCatalog catalog, List<Hit> visible) {
        boolean firstGroup = true;
        for (String layer : catalog.layerNames()) {
            List<Hit> inLayer =
                    visible.stream().filter(h -> h.src.layer().equals(layer)).toList();
            if (inLayer.isEmpty()) continue;
            if (!firstGroup) CliOutput.out();
            firstGroup = false;
            printTable("Library search — " + layer, inLayer);
        }
    }

    private void printTable(String title, List<Hit> visible) {
        List<String> headers = new java.util.ArrayList<>(List.of("Name", "Coordinates"));
        if (showLayer && !groupByLayer) headers.add("Layer");
        headers.add("Cached");
        List<List<String>> rows = new java.util.ArrayList<>();
        for (Hit h : visible) {
            List<String> cells = new java.util.ArrayList<>(List.of(h.name, h.src.module().moduleKey()));
            if (showLayer && !groupByLayer) cells.add(h.src.layer());
            cells.add(String.join(", ", h.cached));
            rows.add(cells);
        }
        cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
        for (String line : cc.jumpkick.cli.tui.BoxTable.render(title, headers, rows)) {
            CliOutput.out(line);
        }
    }

    private static boolean allMatch(List<String> terms, String... fields) {
        for (String t : terms) {
            boolean found = false;
            for (String f : fields) {
                if (f.contains(t)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private record Hit(String name, LibraryCatalog.Source src, List<String> cached) {}
}
