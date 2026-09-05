// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.discovery.ProbeSupport;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.ActiveJavac;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkSelector;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.jdk.LockPinMatch;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk jdk list} — every JDK the probe chain finds on this machine (jk's managed dir, SDKMAN,
 * JBang, mise, asdf, jenv, Homebrew, system paths). With {@code --all}, also lists JDKs available
 * for download from the JetBrains feed for the current OS / arch.
 *
 * <p>The JDK {@code javac} on {@code PATH} resolves to is highlighted as {@code current}; jk's
 * global default is shown as {@code default} only when it differs from the current one. Each row's
 * source column names the tool that owns the install ({@code sdkman}, {@code intellij}, …) rather
 * than the ephemeral {@code $JAVA_HOME} pointer.
 *
 * <p>Renders a box-drawn table grouped by major version. Both modes consult the JetBrains feed (or
 * its cache) so installed point releases that lag the feed can be marked {@code outdated!}. Without
 * {@code --all}, only installed rows are shown; with {@code --all}, a separate {@code available}
 * row is added for each family whose latest feed entry is not yet installed (including when an older
 * patch of that family is already on disk). Network failure degrades to installed-only rows with a
 * stderr warning and no outdated markers.
 */
public final class JdkListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List installed JDKs (--all adds available ones)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Also list downloadable JDKs from the feed", "--all"),
                CommonOpts.jdksDir(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url")
                        .hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file")
                        .hide());
    }

    boolean all;

    @Nullable
    Path jdksDir;

    @Nullable
    URI feedUrl;

    @Nullable
    Path cacheFile;

    enum Status {
        // ACTIVE first so it sorts to the top of its version group and wins the
        // status-priority tie-break (it's also the primary role for styling when
        // a JDK holds several roles).
        ACTIVE("active"),
        DEFAULT("default"),
        NATIVE("native"),
        INSTALLED("installed"),
        OUTDATED("outdated!"),
        AVAILABLE("available");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /**
     * One row in the rendered table. {@code status} is the primary role (for sort + styling); {@code
     * statusLabel} is the displayed text, which may be a composite of roles a single JDK holds at
     * once (e.g. {@code active/native}).
     */
    record Row(int major, String vendor, String spec, Status status, String statusLabel, String location) {}

    /** Build the composite status text from the roles a JDK holds. */
    private static String compositeLabel(boolean active, boolean isDefault, boolean isNative, boolean outdated) {
        StringBuilder sb = new StringBuilder();
        if (active) sb.append("active");
        if (isDefault) {
            if (sb.length() > 0) sb.append('/');
            sb.append("default");
        }
        if (isNative) {
            if (sb.length() > 0) sb.append('/');
            sb.append("native");
        }
        if (outdated) {
            if (sb.length() > 0) sb.append('/');
            sb.append("outdated!");
        }
        return sb.length() == 0 ? "installed" : sb.toString();
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.all = in.isSet("all");
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(CliPaths::abs).orElse(null);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Path jdksRoot = registry.jdksRoot();
        List<JdkHit> installed = registry.listHits();
        // Match the default / native rows by the recorded HOME path (unique per
        // install) rather than the vendor-major identifier (which two installs
        // under different roots can share).
        JdkInventory gd = JdkInventory.of(jdksRoot);
        Path defaultHome = gd.defaultHome().orElse(null);
        Path graalHome = gd.graalHome().orElse(null);
        if (defaultHome == null) {
            defaultHome = gd.defaultId().flatMap(id -> findHome(registry, id)).orElse(null);
        }
        if (graalHome == null) {
            graalHome = gd.graalId().flatMap(id -> findHome(registry, id)).orElse(null);
        }
        // Catalog (feed / cache) is always consulted so lagging point releases can
        // be marked outdated!. --all additionally surfaces available download rows.
        JdkCatalog catalog = fetchCatalogOrNull();

        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        // The "current" JDK is whatever `javac` on PATH resolves to — what this
        // shell actually compiles with, independent of jk's default pointer.
        Path currentHome = ActiveJavac.home().orElse(null);

        List<Row> rows = buildRows(installed, defaultHome, catalog, os, arch, currentHome, graalHome);
        if (!all) {
            // Default list: installed only (with outdated! when the feed is newer).
            rows = rows.stream().filter(r -> r.status() != Status.AVAILABLE).toList();
        }
        if (rows.isEmpty()) {
            String suffix = all ? ", no remote JDKs found" : "";
            CliOutput.out("(no JDKs installed under " + jdksRoot + suffix + ")");
            return 0;
        }

        String title = all ? "All OpenJDKs" : "Installed OpenJDKs";
        CommandWedge.envelopeStart();
        for (String line : renderTable(rows, title)) {
            CliOutput.out(line);
        }
        return 0;
    }

    @Override
    public String toString() {
        return "jdk list";
    }

    static List<Row> buildRows(
            List<JdkHit> installed,
            @Nullable Path defaultHome,
            @Nullable JdkCatalog catalog,
            String os,
            String arch,
            @Nullable Path currentHome,
            @Nullable Path graalHome) {
        // Index catalog entries by installFolderName, restricted to current host.
        Map<String, JdkCatalog.Entry> byInstall = new HashMap<>();
        // Latest non-preview catalog entry per (vendor, product, major) on this host.
        Map<String, JdkCatalog.Entry> latestPerTuple = new LinkedHashMap<>();
        if (catalog != null) {
            for (JdkCatalog.Entry e : catalog.entries()) {
                if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
                byInstall.putIfAbsent(e.installFolderName(), e);
                if (e.preview()) continue;
                String key = familyKey(e);
                JdkCatalog.Entry prior = latestPerTuple.get(key);
                if (prior == null || newerThan(e.version(), prior.version())) {
                    latestPerTuple.put(key, e);
                }
            }
        }

        // Installed → Row. Status precedence: CURRENT (what `javac` on PATH
        // resolves to) wins over DEFAULT (jk's global default) — so the green
        // "default" row only appears when the default JDK isn't the one on PATH.
        // When the feed has a newer point release of the same family, the row is
        // marked outdated! (alone or composed with active/default/native).
        boolean currentShown = false;
        List<Row> rows = new ArrayList<>();
        // Highest installed version per family key — used to decide whether the
        // latest feed entry still needs an "available" row under --all.
        Map<String, String> maxInstalledVersion = new HashMap<>();
        for (JdkHit j : installed) {
            String id = IntellijJdkDir.installDirOf(j.home()).getFileName().toString();
            JdkCatalog.Entry e = byInstall.get(id);
            // Catalog match → catalog's display strings. No match → fall back to the
            // probe's vendor lookup so external installs (SDKMAN, system, mise, …)
            // still get a useful vendor column.
            String vendor = e != null
                    ? e.vendor() + " " + e.product()
                    : (j.vendor() != JdkVendor.UNKNOWN ? j.vendor().displayName() : "");
            int major = e != null ? e.majorVersion() : parseMajor(id);
            boolean isActive = LockPinMatch.sameHome(currentHome, j.home());
            boolean isDefault = LockPinMatch.sameHome(defaultHome, j.home());
            boolean isNative = LockPinMatch.sameHome(graalHome, j.home());
            Optional<JdkCatalog.Entry> latest = e != null
                    ? Optional.ofNullable(latestPerTuple.get(familyKey(e)))
                    : latestPointRelease(catalog, id, os, arch);
            String installedVersion = j.version() != null && !j.version().isBlank() ? j.version() : id;
            boolean outdated = latest.isPresent() && newerThan(latest.get().version(), installedVersion);
            if (latest.isPresent()) {
                String key = familyKey(latest.get());
                String prev = maxInstalledVersion.get(key);
                if (prev == null || newerThan(installedVersion, prev)) {
                    maxInstalledVersion.put(key, installedVersion);
                }
            } else if (e != null) {
                // Exact folder match but no latest? Still track for available suppression.
                String key = familyKey(e);
                String prev = maxInstalledVersion.get(key);
                if (prev == null || newerThan(installedVersion, prev)) {
                    maxInstalledVersion.put(key, installedVersion);
                }
            }
            // A JDK can hold several roles at once; status is the primary (for
            // sort/style), statusLabel the composite shown to the user.
            Status status = isActive
                    ? Status.ACTIVE
                    : isDefault
                            ? Status.DEFAULT
                            : isNative ? Status.NATIVE : outdated ? Status.OUTDATED : Status.INSTALLED;
            if (isActive) currentShown = true;
            rows.add(new Row(
                    major, vendor, id, status, compositeLabel(isActive, isDefault, isNative, outdated), j.source()));
        }

        // The active javac may resolve to a JDK no probe surfaced (e.g. on PATH
        // but outside every manager's root). Synthesize an ACTIVE row so the
        // JDK this shell actually uses is never absent from the list.
        if (currentHome != null && !currentShown) {
            ProbeSupport.discoverJdk(currentHome, "path").ifPresent(hit -> {
                String id =
                        IntellijJdkDir.installDirOf(hit.home()).getFileName().toString();
                String vendor = hit.vendor() != JdkVendor.UNKNOWN ? hit.vendor().displayName() : "";
                boolean d = LockPinMatch.sameHome(defaultHome, hit.home());
                boolean n = LockPinMatch.sameHome(graalHome, hit.home());
                Optional<JdkCatalog.Entry> latest = latestPointRelease(catalog, id, os, arch);
                String installedVersion =
                        hit.version() != null && !hit.version().isBlank() ? hit.version() : id;
                boolean outdated = latest.isPresent() && newerThan(latest.get().version(), installedVersion);
                if (latest.isPresent()) {
                    String key = familyKey(latest.get());
                    String prev = maxInstalledVersion.get(key);
                    if (prev == null || newerThan(installedVersion, prev)) {
                        maxInstalledVersion.put(key, installedVersion);
                    }
                }
                rows.add(new Row(
                        parseMajor(id), vendor, id, Status.ACTIVE, compositeLabel(true, d, n, outdated), hit.source()));
            });
        }

        // Catalog → available rows for each (vendor, product, major) whose latest
        // feed entry is not already satisfied by an install (missing family, or
        // installed but lagging a newer point release — e.g. temurin-25.0.3 on
        // disk while the feed has 25.0.4).
        if (catalog != null) {
            for (JdkCatalog.Entry e : latestPerTuple.values()) {
                String key = familyKey(e);
                String installedMax = maxInstalledVersion.get(key);
                if (installedMax != null && !newerThan(e.version(), installedMax)) {
                    continue; // already at or past the latest feed version
                }
                // When maxInstalledVersion missed (no family-key match via latest),
                // also suppress if any installed id belongs to this feed family and
                // is already current — covered above when latestPointRelease keyed
                // the install. Fallback: family match by suggested_sdk_name.
                if (installedMax == null && isSatisfiedByInstalled(installed, e)) {
                    continue;
                }
                rows.add(new Row(
                        e.majorVersion(),
                        e.vendor() + " " + e.product(),
                        e.installFolderName(),
                        Status.AVAILABLE,
                        "available",
                        "download"));
            }
        }

        // Sort: major desc, then status priority (active > default > … > available,
        // via enum ordinal), then vendor alphabetical.
        rows.sort(Comparator.comparingInt(Row::major)
                .reversed()
                .thenComparingInt((Row r) -> r.status().ordinal())
                .thenComparing(Row::vendor, Comparator.nullsLast(String::compareTo)));
        return rows;
    }

    private static String familyKey(JdkCatalog.Entry e) {
        return e.vendor() + " " + e.product() + "|" + e.majorVersion();
    }

    /**
     * Highest-versioned non-preview catalog entry on this host that belongs to the same family as
     * the installed id (its {@code suggested_sdk_name} is a delimiter-bounded prefix of the id).
     * Same matching rules as {@code jk jdk update}.
     */
    private static Optional<JdkCatalog.Entry> latestPointRelease(
            @Nullable JdkCatalog catalog, String installedId, String os, String arch) {
        if (catalog == null) return Optional.empty();
        JdkCatalog.Entry best = null;
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            if (!belongsToFamily(installedId, e.suggestedSdkName())) continue;
            if (best == null || newerThan(e.version(), best.version())) best = e;
        }
        return Optional.ofNullable(best);
    }

    /** Does install id {@code id} belong to the family named by {@code suggested}? */
    private static boolean belongsToFamily(String id, String suggested) {
        if (suggested == null || suggested.isEmpty()) return false;
        return id.equals(suggested)
                || id.startsWith(suggested + ".")
                || id.startsWith(suggested + "-")
                || id.startsWith(suggested + "+");
    }

    /**
     * True when some installed hit already matches {@code e}'s family and is at least as new as
     * {@code e.version()} (suppresses a redundant available row).
     */
    private static boolean isSatisfiedByInstalled(List<JdkHit> installed, JdkCatalog.Entry e) {
        for (JdkHit j : installed) {
            String id = IntellijJdkDir.installDirOf(j.home()).getFileName().toString();
            if (!belongsToFamily(id, e.suggestedSdkName())) continue;
            String ver = j.version() != null && !j.version().isBlank() ? j.version() : id;
            if (!newerThan(e.version(), ver)) return true;
        }
        return false;
    }

    /** {@code a > b} by {@link JdkSelector#versionKey} ordering ({@code 25.0.10 > 25.0.9}). */
    private static boolean newerThan(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        return JdkSelector.versionKey(a).compareTo(JdkSelector.versionKey(b)) > 0;
    }

    /**
     * True when {@code currentHome} and a probe-discovered {@code hitHome} point at the same JDK.
     * Both are normally already canonical, but we canonicalise defensively (each may be a symlink
     * path) before comparing.
     */
    /** Resolve an install identifier to its home via the registry (first match), or empty. */
    private static Optional<Path> findHome(JdkRegistry registry, String id) {
        try {
            return registry.find(id).map(InstalledJdk::home);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Extract the major version from an install identifier when no catalog match is available.
     * Handles modern names ({@code temurin-25.0.3} → 25, {@code graalvm-jdk-21} → 21) and the legacy
     * Java-8 form ({@code temurin-1.8.0_492} → 8). Returns 0 when no number is found.
     */
    static int parseMajor(String identifier) {
        var m = Pattern.compile("(\\d+)(?:[._](\\d+))?").matcher(identifier);
        if (!m.find()) return 0;
        int first = Integer.parseInt(m.group(1));
        if (first == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2));
        }
        return first;
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    /** Render the table as a sequence of ANSI-styled lines, ready to println. */
    static List<String> renderTable(List<Row> rows, String title) {
        Table table = new Table(title)
                .columns(
                        new Table.Column("Version", Table.Align.CENTER),
                        new Table.Column("Vendor"),
                        new Table.Column("Spec"),
                        new Table.Column("Status"),
                        new Table.Column("Source"));
        Map<Integer, List<Row>> grouped = new TreeMap<>(Comparator.reverseOrder());
        for (Row r : rows)
            grouped.computeIfAbsent(r.major(), k -> new ArrayList<>()).add(r);
        boolean firstGroup = true;
        for (var entry : grouped.entrySet()) {
            if (!firstGroup) table.row(Table.Row.separator());
            firstGroup = false;
            var groupRows = entry.getValue();
            for (int i = 0; i < groupRows.size(); i++) {
                Row r = groupRows.get(i);
                String version = i == 0 ? String.valueOf(r.major()) : "";
                table.row(jdkRow(version, r));
            }
        }
        return table.render(RenderContext.current());
    }

    private static Table.Row jdkRow(String version, Row r) {
        String label = r.statusLabel();
        boolean active = label.contains("active");
        boolean italic = label.contains("default");
        boolean bold = active;
        Rgb band = active ? Theme.active().darkBlackColor() : null;
        Status status = r.status();
        RichText loc;
        String location = r.location();
        if (location == null || location.isEmpty()) {
            loc = RichText.empty();
        } else {
            var locStyle = status == Status.AVAILABLE
                    ? Theme.active().darkGray()
                    : Theme.active().path();
            loc = painted(location, locStyle, italic, bold, band);
        }
        Table.Row row = Table.Row.data(
                painted(version, Style.EMPTY, italic, bold, band),
                painted(r.vendor() == null ? "" : r.vendor(), Style.EMPTY, italic, bold, band),
                painted(r.spec(), Theme.active().settled(), italic, bold, band),
                RichText.ansi(statusPainted(label, italic, bold, band)),
                loc);
        return active ? row.emphasized() : row;
    }

    private static RichText painted(String text, Style base, boolean italic, boolean bold, @Nullable Rgb band) {
        String s = text == null ? "" : text;
        if (!Theme.active().isAnsi()) return RichText.plain(s);
        Style style = deco(base, italic, bold);
        if (band != null) style = Theme.active().withBackground(style, band);
        return RichText.ansi(Theme.colorize(s, style));
    }

    /** Layer the active-row indigo band background onto a cell style (no-op when not banded). */
    private static Style banded(Style base, @Nullable Rgb band) {
        return band == null ? base : Theme.active().withBackground(base, band);
    }

    /** Layer the row-level italic (active) / bold (default) attributes onto a cell style. */
    private static Style deco(Style base, boolean italic, boolean bold) {
        if (italic) base = base.italic();
        if (bold) base = base.bold();
        return base;
    }

    /**
     * Render the (possibly composite) status label with a distinct color per role — active =
     * bright-cyan+bold, default = bright-yellow, native = bright-green — joined by a dim slash, then
     * padded to the column width. The row's italic/bold emphasis is layered on so the whole line
     * reads uniformly.
     */
    private static String statusPainted(String label, boolean italic, boolean bold, @Nullable Rgb band) {
        String[] parts = label.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(Theme.colorize("/", banded(Theme.active().darkGray(), band)));
            sb.append(Theme.colorize(parts[i], banded(deco(segmentStyle(parts[i]), italic, bold), band)));
        }
        return sb.toString();
    }

    private static Style segmentStyle(String role) {
        return switch (role) {
            case "active" -> Theme.active().brightCyan().bold();
            case "default" -> Theme.active().brightYellow();
            case "native" -> Theme.active().brightGreen();
            case "outdated!" -> Theme.active().error(); // red — upgrade this install
            case "available" -> Theme.active().darkGray();
            default -> Theme.active().completedStep(); // "installed"
        };
    }

    // ---------------------------------------------------------------
    // Helpers / catalog plumbing
    // ---------------------------------------------------------------

    private @Nullable JdkCatalog fetchCatalogOrNull() {
        if (!HostPlatform.supported()) return null;
        try {
            boolean refresh = SessionContext.current().config().forceOr(false);
            JdkCatalogClient client = (feedUrl != null
                            ? new JdkCatalogClient(
                                    new Http(),
                                    feedUrl,
                                    cacheFile != null ? cacheFile : ephemeralCachePath(),
                                    Duration.ZERO)
                            : new JdkCatalogClient())
                    .onWarning(CliOutput.stderr()::println);
            // --all is the "show me everything" view: every vendor/product at
            // every major >= 17, not just jk's curated LTS-or-latest set.
            return client.fetch(refresh, /* firstClassOnly= */ false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            CommandWedge.printFail(
                    "JDK", "JetBrains feed unreachable (" + e.getMessage() + "); showing installed JDKs only.");
            return null;
        }
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        Files.delete(tmp);
        return tmp;
    }
}
