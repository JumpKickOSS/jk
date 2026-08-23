// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk outdated} — read-only report of declared deps with newer versions than {@code jk-lock.toml}
 * pins (Current / Compatible / Latest; optional Tip). Engine-hosted; writes nothing. At a workspace
 * root, cascades over every module.
 *
 * <p>Exit 0 on success whether or not any row is outdated (inspect JSON or the table for drift).
 * Does not re-resolve or rewrite the lock — use {@code jk update} after review. Machine output:
 * {@code --output json} emits a JSON array of row objects (see guide).
 */
public final class OutdatedCommand implements CliCommand {

    private boolean showTip;
    private boolean excludeUpToDate;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "outdated";
    }

    @Override
    public String description() {
        return "Report dependencies with newer versions available";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Show Tip column (prerelease / git HEAD)", "--show-tip"),
                Opt.flag("Hide deps already on newest compatible", "--exclude-up-to-date"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.excludeUpToDate = in.isSet("exclude-up-to-date");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Outdated", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        // Best-effort: outdated resolves against its repos independently — a lockless project
        // whose freshen cannot resolve (e.g. --repo-url world) still reports (JK-2178).
        cc.jumpkick.cli.EnsureFreshLock.ensureBestEffort(dir, cache, global, "Outdated", repoUrl);

        OutdatedReport report;
        report = cc.jumpkick.cli.engine.EngineClient.runOutdated(
                cc.jumpkick.engine.EnginePaths.current(),
                new cc.jumpkick.cli.engine.EngineRequests.OutdatedRequest(
                        dir, cache, repoUrl, global.offline, global.force));

        if (report.error() != null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Outdated", report.error());
            return Exit.CONFIG;
        }

        List<OutdatedReport.Row> rows = report.rows();
        if (excludeUpToDate) {
            rows = rows.stream().filter(r -> !upToDate(r)).toList();
        }
        if (global.outputIsJson()) {
            CliOutput.outRaw(toJson(rows));
            return Exit.SUCCESS;
        }
        if (global.offline) {
            CliOutput.out("Note: offline mode — Compatible / Latest come from the local cache and repos only;"
                    + " unreachable remotes may look up-to-date.");
        }
        if (rows.isEmpty()) {
            CliOutput.out(excludeUpToDate ? "(no outdated dependencies)" : "(no dependencies to check)");
            return Exit.SUCCESS;
        }
        cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
        for (String line : renderTable(rows, report.workspace(), showTip, "Dependency versions")) {
            CliOutput.out(line);
        }
        // Footer: lockfile-respecting workflow + graph inspection.
        CliOutput.out("Next: review with `jk why <coord>` / `jk tree`, then `jk update` only when you intend"
                + " to re-resolve (lockfile is law).");
        return Exit.SUCCESS;
    }

    @Override
    public String toString() {
        return "outdated";
    }

    // Version comparison (normalizes git tag names like "v1.2.3")

    /** True when {@code a} is a strictly-higher version than {@code b} (both version-like). */
    private static boolean ahead(String a, String b) {
        String na = norm(a);
        String nb = norm(b);
        return na != null && nb != null && Versions.compare(na, nb) > 0;
    }

    /** Normalize a cell to a comparable Maven version, or null when it isn't one ("", "tip", tag text). */
    private static String norm(String v) {
        if (v == null || v.isEmpty() || v.equals("tip")) return null;
        String n = GitVersion.fromTag(v); // "v1.2.3" -> "1.2.3"; leaves Maven versions unchanged
        return (n.isEmpty() || !Character.isDigit(n.charAt(0))) ? null : n;
    }

    private static boolean upToDate(OutdatedReport.Row r) {
        if (norm(r.current()) == null) return false; // unlocked / unknown current — keep it visible
        return !ahead(r.compatible(), r.current()) && !ahead(r.latest(), r.current());
    }

    // JSON

    private static String toJson(List<OutdatedReport.Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            OutdatedReport.Row r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"module\":")
                    .append(Jsonl.quote(r.moduleLabel()))
                    .append(",\"dependency\":")
                    .append(Jsonl.quote(r.coordinate()))
                    .append(",\"display\":")
                    .append(Jsonl.quote(r.display()))
                    .append(",\"scope\":")
                    .append(Jsonl.quote(r.scope()))
                    .append(",\"current\":")
                    .append(Jsonl.quote(r.current()))
                    .append(",\"compatible\":")
                    .append(Jsonl.quote(r.compatible()))
                    .append(",\"latest\":")
                    .append(Jsonl.quote(r.latest()))
                    .append(",\"tip\":")
                    .append(Jsonl.quote(r.tip()))
                    .append('}');
        }
        return sb.append(']').toString();
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
            Style[] styles = new Style[n];
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
            Style[] styles = styleRows.get(i);
            RichText[] rich = new RichText[n];
            for (int c = 0; c < n; c++) {
                rich[c] = styledCell(cells[c], styles[c]);
            }
            table.row(rich);
        }
        return table.render(RenderContext.current());
    }

    private static RichText styledCell(String text, Style style) {
        if (style == null || !Theme.active().isAnsi()) return RichText.plain(text);
        return RichText.ansi(Theme.colorize(text, style));
    }

    private static String disp(String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }
}
