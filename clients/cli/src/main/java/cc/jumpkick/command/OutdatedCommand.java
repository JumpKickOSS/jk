// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedStyle;

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
                Opt.flag("Add a Tip column for the non-stable frontier (prerelease / git HEAD).", "--show-tip"),
                Opt.flag("Hide dependencies already on the newest compatible version.", "--exclude-up-to-date"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.excludeUpToDate = in.isSet("exclude-up-to-date");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail("Outdated", "no jk.toml in " + PathDisplay.styledRaw(dir)));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(dir, cache, global, "Outdated");
        if (lockCode != 0) return lockCode;

        OutdatedReport report;
        report = cc.jumpkick.cli.engine.EngineClient.runOutdated(
                cc.jumpkick.engine.EnginePaths.current(),
                new cc.jumpkick.cli.engine.EngineClient.OutdatedRequest(
                        dir, cache, repoUrl, global.offline, global.force));

        if (report.error() != null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Outdated", report.error()));
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
        List<AttributedStyle[]> styleRows = new ArrayList<>();
        List<Boolean> dividerBefore = new ArrayList<>();
        String prevModule = null;
        boolean first = true;
        for (OutdatedReport.Row r : rows) {
            boolean newGroup = workspace && !r.moduleLabel().equals(prevModule);
            dividerBefore.add(!first && newGroup);
            String[] cells = new String[n];
            AttributedStyle[] styles = new AttributedStyle[n];
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

        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = headers.get(i).length();
        for (String[] cells : cellRows) {
            for (int i = 0; i < n; i++) w[i] = Math.max(w[i], cells[i].length());
        }
        int inner = innerWidth(w);

        List<String> out = new ArrayList<>();
        out.add(cc.jumpkick.cli.tui.BoxTable.titleBar(title, inner + 2));
        out.add(divider("├", "┬", "┤", w));
        out.add(headerRow(headers, w));
        out.add(divider("├", "┼", "┤", w));
        for (int i = 0; i < cellRows.size(); i++) {
            if (dividerBefore.get(i)) out.add(divider("├", "┼", "┤", w));
            out.add(dataRow(cellRows.get(i), styleRows.get(i), w));
        }
        out.add(divider("╰", "┴", "╯", w));
        return out;
    }

    private static String disp(String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }

    private static int innerWidth(int[] widths) {
        int sum = 0;
        for (int c : widths) sum += c + 2;
        return sum + (widths.length - 1);
    }

    private static String divider(String left, String junction, String right, int[] widths) {
        boolean ansi = Theme.active().isAnsi();
        var sb = new StringBuilder(ansi ? left : "+");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ansi ? "─" : "-").repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? (ansi ? right : "+") : (ansi ? junction : "+"));
        }
        return ansi ? Theme.colorize(sb.toString(), Theme.active().darkGray()) : sb.toString();
    }

    private static String headerRow(List<String> headers, int[] widths) {
        String bar =
                Theme.active().isAnsi() ? Theme.colorize("│", Theme.active().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < headers.size(); i++) {
            sb.append(" ")
                    .append(padRight(headers.get(i), widths[i]))
                    .append(" ")
                    .append(bar);
        }
        return sb.toString();
    }

    private static String dataRow(String[] cells, AttributedStyle[] styles, int[] widths) {
        String bar =
                Theme.active().isAnsi() ? Theme.colorize("│", Theme.active().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < cells.length; i++) {
            sb.append(" ")
                    .append(styled(cells[i], widths[i], styles[i]))
                    .append(" ")
                    .append(bar);
        }
        return sb.toString();
    }

    private static String styled(String text, int width, AttributedStyle style) {
        String padded = padRight(text, width);
        if (style == null || !Theme.active().isAnsi()) return padded;
        return Theme.colorize(padded, style);
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
