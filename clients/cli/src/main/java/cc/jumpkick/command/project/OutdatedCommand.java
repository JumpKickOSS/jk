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
import cc.jumpkick.cli.tui.OutdatedBar;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GroupInitials;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk outdated} — read-only report of declared deps an update would move (Current / Compatible /
 * Latest; optional Tip). Engine-hosted; writes nothing but the results file. At a workspace root,
 * cascades over every module and prints one row per coordinate with the module count and the spread
 * of pins; {@code --by-module} prints a row per module instead. Rows already at their newest are
 * hidden unless {@code --all}.
 *
 * <p>Exit 0 on success whether or not any row can move (a non-empty JSON array is drift). Does not
 * re-resolve or rewrite the lock — use {@code jk update} after review. Machine output: {@code
 * --output json} emits a JSON array of row objects (see guide).
 */
public final class OutdatedCommand implements CliCommand {

    private boolean showTip;
    private boolean all;
    private boolean byModule;
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
                Opt.flag("A row per module and dependency, not per coordinate", "--by-module"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.all = in.isSet("all");
        this.byModule = in.isSet("by-module");
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

        // The bar takes the row before the request goes out and gives it back before the table
        // prints, so the report lands where the chip was. JSON stdout carries the array alone.
        OutdatedReport report;
        EngineRequests.OutdatedRequest request =
                new EngineRequests.OutdatedRequest(dir, cache, repoUrl, global.offline, global.force);
        if (global.outputIsJson()) {
            report = EngineClient.runOutdated(EnginePaths.current(), request, EngineRequests.OutdatedHandler.NONE);
        } else {
            try (OutdatedBar bar = OutdatedBar.show(CliOutput.stdout())) {
                report = EngineClient.runOutdated(EnginePaths.current(), request, bar::update);
            }
        }

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
            printReportFile(dir);
            return Exit.SUCCESS;
        }
        CommandWedge.envelopeStart();
        List<String> table = byModule
                ? renderByModule(rows, report.workspace(), showTip, "Dependency versions")
                : renderRollup(
                        all ? report.rollup() : report.movableRollup(),
                        report.workspace(),
                        showTip,
                        "Dependency versions");
        for (String line : table) CliOutput.out(line);
        // Footer: lockfile-respecting workflow + graph inspection.
        CliOutput.out("Next: review with `jk why <coord>` / `jk tree`; `jk update [name…]` moves the declared"
                + " pins to Compatible and relocks, `jk update --major` to Latest.");
        printReportFile(dir);
        return Exit.SUCCESS;
    }

    /** Written by the engine on every successful run; mirrors the engine's file name. */
    static final String REPORT_FILE = "jk-outdated-dependencies.md";

    /** The engine wrote the whole report under the owning root's {@code target/}; say where. */
    private static void printReportFile(Path dir) {
        Path root = dir;
        try {
            root = WorkspaceLocator.owningRoot(dir).orElse(dir);
        } catch (IOException e) {
            Log.debug("printReportFile: workspace root lookup failed", e);
        }
        Path file = root.resolve(BuildLayout.TARGET).resolve(REPORT_FILE);
        if (Files.exists(file)) CliOutput.out("Report: " + PathDisplay.styled(file, dir));
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

    // Rendering — box-drawn tables mirroring JdkListCommand's style. The rollup has one row per
    // coordinate: Dependency Current Compatible Latest [Tip?] [Modules] Scope. The by-module view
    // has one row per module and dependency, each module a full-width group header above its rows.

    private static final String NONE = "—";

    /** A version cell that repeats the one to its left. */
    static final String SAME = "=";

    /** Widest Dependency cell; a longer catalog name or coordinate ends in an ellipsis. */
    static final int DEPENDENCY_COLUMNS = 30;

    /** Widest version cell; timestamped qualifiers like {@code 7.7.1.202607240634-r} are cut here. */
    static final int VERSION_COLUMNS = 14;

    /** Widest spread cell ({@code 1.1.1 ×11 · 1.2.0 ×1}); a wider spread ends in an ellipsis. */
    static final int SPREAD_COLUMNS = 24;

    /** The rollup carries a Modules column, so its Dependency and version cells give up a little. */
    static final int ROLLUP_DEPENDENCY_COLUMNS = 26;

    static final int ROLLUP_VERSION_COLUMNS = 12;

    static List<String> renderRollup(
            List<OutdatedReport.Rollup> rollups, boolean workspace, boolean showTip, String title) {
        List<String> headers = new ArrayList<>();
        headers.add("Dependency");
        headers.add("Current");
        headers.add("Compatible");
        headers.add("Latest");
        if (showTip) headers.add("Tip");
        if (workspace) headers.add("Modules");
        headers.add("Scope");
        int n = headers.size();

        Table table = new Table(title).columns(headers.toArray(String[]::new));
        for (OutdatedReport.Rollup r : rollups) {
            RichText[] rich = new RichText[n];
            int c = 0;
            boolean hasShort = !r.display().isEmpty();
            rich[c++] = styledCell(
                    clip(hasShort ? r.display() : GroupInitials.module(r.coordinate()), ROLLUP_DEPENDENCY_COLUMNS),
                    hasShort ? Theme.active().path().italic() : Theme.active().path());
            String current = OutdatedReport.spreadText(r.current());
            String compatible = OutdatedReport.spreadText(r.compatible());
            rich[c++] = styledCell(
                    r.currentDiffers() ? clip(current, SPREAD_COLUMNS) : version(current, null, ROLLUP_VERSION_COLUMNS),
                    r.currentDiffers() ? Theme.active().warning() : null);
            boolean compatibleDiffers = r.compatible().size() > 1;
            rich[c++] = styledCell(
                    compatibleDiffers
                            ? clip(compatible, SPREAD_COLUMNS)
                            : version(compatible, current, ROLLUP_VERSION_COLUMNS),
                    compatibleDiffers || anyAhead(r.compatible(), r.current())
                            ? Theme.active().brightYellow()
                            : null);
            rich[c++] = styledCell(
                    version(r.latest(), compatibleDiffers ? null : compatible, ROLLUP_VERSION_COLUMNS),
                    anyAhead(List.of(new OutdatedReport.Spread(r.latest(), 1)), r.compatible())
                            ? Theme.active().brightCyan()
                            : null);
            if (showTip)
                rich[c++] = styledCell(
                        version(r.tip(), null, ROLLUP_VERSION_COLUMNS),
                        Theme.active().darkGray());
            if (workspace) rich[c++] = styledCell(Integer.toString(r.modules().size()), null);
            rich[c] = styledCell(String.join(" · ", r.scopes()), Theme.active().darkGray());
            table.row(rich);
        }
        return table.render(RenderContext.current());
    }

    /** True when some version in {@code a} is strictly ahead of some version in {@code b}. */
    private static boolean anyAhead(List<OutdatedReport.Spread> a, List<OutdatedReport.Spread> b) {
        for (OutdatedReport.Spread x : a) {
            for (OutdatedReport.Spread y : b) {
                if (ahead(x.version(), y.version())) return true;
            }
        }
        return false;
    }

    static List<String> renderByModule(
            List<OutdatedReport.Row> rows, boolean workspace, boolean showTip, String title) {
        List<String> headers = new ArrayList<>();
        headers.add("Dependency");
        headers.add("Current");
        headers.add("Compatible");
        headers.add("Latest");
        if (showTip) headers.add("Tip");
        headers.add("Scope");
        int n = headers.size();

        Table table = new Table(title).columns(headers.toArray(String[]::new));
        String prevModule = null;
        for (OutdatedReport.Row r : rows) {
            if (workspace && !r.moduleLabel().equals(prevModule)) {
                table.row(Table.Row.span(
                        Table.Cell.of(styledCell(r.moduleLabel(), Theme.active().brightYellow()))
                                .span(n)));
                prevModule = r.moduleLabel();
            }
            RichText[] rich = new RichText[n];
            int c = 0;
            boolean hasShort = !r.display().isEmpty();
            rich[c++] = styledCell(
                    clip(hasShort ? r.display() : GroupInitials.module(r.coordinate()), DEPENDENCY_COLUMNS),
                    hasShort ? Theme.active().path().italic() : Theme.active().path());
            rich[c++] = styledCell(version(r.current(), null), null);
            rich[c++] = styledCell(
                    version(r.compatible(), r.current()),
                    ahead(r.compatible(), r.current()) ? Theme.active().brightYellow() : null);
            rich[c++] = styledCell(
                    version(r.latest(), r.compatible()),
                    ahead(r.latest(), r.compatible()) ? Theme.active().brightCyan() : null);
            if (showTip)
                rich[c++] = styledCell(version(r.tip(), null), Theme.active().darkGray());
            rich[c] = styledCell(r.scope(), Theme.active().darkGray());
            table.row(rich);
        }
        return table.render(RenderContext.current());
    }

    /** The version cell: {@value #SAME} when it repeats {@code left}, the dash when empty, else clipped. */
    private static String version(@Nullable String v, @Nullable String left) {
        return version(v, left, VERSION_COLUMNS);
    }

    private static String version(@Nullable String v, @Nullable String left, int columns) {
        if (v == null || v.isEmpty()) return NONE;
        if (v.equals(left)) return SAME;
        return clip(v, columns);
    }

    private static String clip(String text, int columns) {
        return RenderContext.truncateVisible(text, columns);
    }

    private static RichText styledCell(String text, @Nullable Style style) {
        if (style == null || !Theme.active().isAnsi()) return RichText.plain(text);
        return RichText.ansi(Theme.colorize(text, style));
    }
}
