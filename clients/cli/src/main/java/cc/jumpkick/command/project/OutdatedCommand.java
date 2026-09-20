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
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.GroupInitials;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
    private @Nullable URI repoUrl;
    private @Nullable Path cacheDir;
    private @Nullable GlobalOptions global;

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
                CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.excludeUpToDate = in.isSet("exclude-up-to-date");
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

    // Version comparison (normalizes git tag names like "v1.2.3")

    /** True when {@code a} is a strictly-higher version than {@code b} (both version-like). */
    private static boolean ahead(@Nullable String a, @Nullable String b) {
        String na = norm(a);
        String nb = norm(b);
        return na != null && nb != null && Versions.compare(na, nb) > 0;
    }

    /** Normalize a cell to a comparable Maven version, or null when it isn't one ("", "tip", tag text). */
    private static @Nullable String norm(@Nullable String v) {
        if (v == null || v.isEmpty() || v.equals("tip")) return null;
        String n = GitVersion.fromTag(v); // "v1.2.3" -> "1.2.3"; leaves Maven versions unchanged
        return (n.isEmpty() || !Character.isDigit(n.charAt(0))) ? null : n;
    }

    private static boolean upToDate(OutdatedReport.Row r) {
        if (norm(r.current()) == null) return false; // unlocked / unknown current — keep it visible
        return !ahead(r.compatible(), r.current()) && !ahead(r.latest(), r.current());
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
    // Columns: Dependency Current Compatible Latest [Tip?] Scope. In a workspace each module is a
    // full-width group header above its rows rather than a column of its own.

    private static final String NONE = "—";

    /** A version cell that repeats the one to its left. */
    static final String SAME = "=";

    /** Widest Dependency cell; a longer catalog name or coordinate ends in an ellipsis. */
    static final int DEPENDENCY_COLUMNS = 30;

    /** Widest version cell; timestamped qualifiers like {@code 7.7.1.202607240634-r} are cut here. */
    static final int VERSION_COLUMNS = 14;

    static List<String> renderTable(List<OutdatedReport.Row> rows, boolean workspace, boolean showTip, String title) {
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
        if (v == null || v.isEmpty()) return NONE;
        if (v.equals(left)) return SAME;
        return clip(v, VERSION_COLUMNS);
    }

    private static String clip(String text, int columns) {
        return RenderContext.truncateVisible(text, columns);
    }

    private static RichText styledCell(String text, @Nullable Style style) {
        if (style == null || !Theme.active().isAnsi()) return RichText.plain(text);
        return RichText.ansi(Theme.colorize(text, style));
    }

    private static String disp(@Nullable String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }
}
