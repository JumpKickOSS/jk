// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk outdated} — read-only report of declared deps an update would move (Current / Compatible /
 * Latest; optional Tip). Engine-hosted; writes nothing. At a workspace root, cascades over every
 * module. Rows already at their newest are hidden unless {@code --all}.
 *
 * <p>Exit 0 on success whether or not any row can move (a non-empty JSON array is drift). Does not
 * re-resolve or rewrite the lock — use {@code jk update} after review. Machine output: {@code
 * --output json} emits a JSON array of row objects (see guide).
 */
public final class OutdatedCommand implements CliCommand {

    private boolean showTip;
    private boolean all;
    private @Nullable URI repoUrl;
    private @Nullable Path cacheDir;
    private @Nullable GlobalOptions global;

    @Override
    public String name() {
        return "outdated";
    }

    @Override
    public String description() {
        return "Report dependencies an update would move";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Show Tip column (prerelease / git HEAD)", "--show-tip"),
                Opt.flag("Every dependency, up to date included", "--all"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.all = in.isSet("all");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(ManifestPaths.manifestIn(dir))) {
            CommandWedge.printFail("Outdated", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        // Best-effort: outdated resolves against its repos independently — a lockless project
        // whose freshen cannot resolve (e.g. --repo-url world) still reports.
        EnsureFreshLock.ensureBestEffort(dir, cache, global, "Outdated", repoUrl);

        OutdatedReport report;
        report = EngineClient.runOutdated(
                EnginePaths.current(),
                new EngineRequests.OutdatedRequest(dir, cache, repoUrl, global.offline, global.force));

        if (report.error() != null) {
            CommandWedge.printFail("Outdated", report.error());
            return Exit.CONFIG;
        }

        int checked = report.rows().size();
        List<OutdatedReport.Row> rows = all ? report.rows() : report.movable();
        if (global.outputIsJson()) {
            CliOutput.outRaw(toJson(rows));
            return Exit.SUCCESS;
        }
        if (global.offline) {
            CliOutput.out("Note: offline mode — Compatible / Latest come from the local cache and repos only;"
                    + " unreachable remotes may look up-to-date.");
        }
        if (rows.isEmpty()) {
            CliOutput.out(
                    checked == 0
                            ? "(no dependencies to check)"
                            : "(all " + checked + (checked == 1 ? " dependency" : " dependencies") + " up to date)");
            return Exit.SUCCESS;
        }
        CommandWedge.envelopeStart();
        for (String line : renderTable(rows, report.workspace(), showTip, "Dependency versions")) {
            CliOutput.out(line);
        }
        // Footer: lockfile-respecting workflow + graph inspection.
        CliOutput.out("Next: review with `jk why <coord>` / `jk tree`; `jk update [name…]` moves the declared"
                + " pins to Compatible and relocks, `jk update --major` to Latest.");
        return Exit.SUCCESS;
    }

    @Override
    public String toString() {
        return "outdated";
    }

    private static boolean ahead(@Nullable String a, @Nullable String b) {
        return OutdatedReport.ahead(a, b);
    }

    // JSON

    public static String toJson(List<OutdatedReport.Row> rows) {
        List<String> items = new ArrayList<>();
        for (OutdatedReport.Row r : rows) {
            items.add(JsonFields.object()
                    .string("module", r.moduleLabel())
                    .string("dependency", r.coordinate())
                    .string("display", r.display())
                    .string("scope", r.scope())
                    .string("current", r.current())
                    .string("compatible", r.compatible())
                    .string("latest", r.latest())
                    .string("tip", r.tip())
                    .finish());
        }
        return "[" + String.join(",", items) + "]";
    }

    // Rendering — box-drawn table mirroring JdkListCommand's style.
    // Columns are dynamic: [Module?] Dependency Current Compatible Latest [Tip?] Scope.

    private static final String NONE = "—";

    static List<String> renderTable(List<OutdatedReport.Row> rows, boolean workspace, boolean showTip, String title) {
        List<String> headers = new ArrayList<>();
        if (workspace) headers.add("Module");
        headers.add("Dependency");
        headers.add("Current");
        headers.add("Compatible");
        headers.add("Latest");
        if (showTip) headers.add("Tip");
        headers.add("Scope");
        int n = headers.size();

        List<String[]> cellRows = new ArrayList<>();
        List<Style[]> styleRows = new ArrayList<>();
        List<Boolean> dividerBefore = new ArrayList<>();
        String prevModule = null;
        boolean first = true;
        for (OutdatedReport.Row r : rows) {
            boolean newGroup = workspace && !r.moduleLabel().equals(prevModule);
            dividerBefore.add(!first && newGroup);
            String[] cells = new String[n];
            @Nullable Style[] styles = new Style[n];
            int c = 0;
            if (workspace) {
                cells[c] = newGroup ? r.moduleLabel() : "";
                styles[c] = Theme.active().brightYellow();
                c++;
            }
            boolean hasShort = !r.display().isEmpty();
            cells[c] = hasShort ? r.display() : r.coordinate();
            styles[c] =
                    hasShort ? Theme.active().path().italic() : Theme.active().path();
            c++;
            cells[c] = disp(r.current());
            styles[c] = null;
            c++;
            cells[c] = disp(r.compatible());
            styles[c] = ahead(r.compatible(), r.current()) ? Theme.active().brightYellow() : null;
            c++;
            cells[c] = disp(r.latest());
            styles[c] = ahead(r.latest(), r.compatible()) ? Theme.active().brightCyan() : null;
            c++;
            if (showTip) {
                cells[c] = disp(r.tip());
                styles[c] = Theme.active().darkGray();
                c++;
            }
            cells[c] = r.scope();
            styles[c] = Theme.active().darkGray();
            cellRows.add(cells);
            styleRows.add(styles);
            prevModule = r.moduleLabel();
            first = false;
        }

        Table table = new Table(title).columns(headers.toArray(String[]::new));
        for (int i = 0; i < cellRows.size(); i++) {
            if (dividerBefore.get(i)) table.row(Table.Row.separator());
            String[] cells = cellRows.get(i);
            @Nullable Style[] styles = styleRows.get(i);
            RichText[] rich = new RichText[n];
            for (int c = 0; c < n; c++) {
                rich[c] = styledCell(cells[c], styles[c]);
            }
            table.row(rich);
        }
        return table.render(RenderContext.current());
    }

    private static RichText styledCell(String text, @Nullable Style style) {
        if (style == null || !Theme.active().isAnsi()) return RichText.plain(text);
        return RichText.ansi(Theme.colorize(text, style));
    }

    private static String disp(@Nullable String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }
}
