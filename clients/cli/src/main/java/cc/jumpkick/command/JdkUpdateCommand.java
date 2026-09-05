// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.engine.EngineCatalogFreshen;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JdkDownloadBar;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Os;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jdk.JdkGarbage;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkSelector;
import cc.jumpkick.jdk.JdkService;
import cc.jumpkick.jdk.StableJdkPointer;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk jdk update [spec]} (alias {@code upgrade}) — refresh jk-managed JDKs to the latest
 * point release of their own family and major.
 *
 * <p>Only installs jk owns (under {@code the managed JDK root}, source {@code "jk"}) are touched; SDKMAN /
 * IntelliJ / system / {@code $JAVA_HOME} JDKs are left alone. With no spec every managed JDK is
 * considered; a spec narrows the set with the usual flexible matcher ({@code 25} = major 25 of any
 * vendor, {@code temurin} = all Temurin, {@code temurin-25} = Temurin 25).
 *
 * <p>Each match is updated <em>within its family+major</em>: {@code temurin-25.0.2 →
 * temurin-25.0.3}, never a major bump and never a vendor switch. The newer release is installed and
 * the superseded one removed; if a removed install was the global default, the default is
 * re-pointed at its replacement. The planned changes are shown and confirmed ({@code [Y/n]}) unless
 * {@code --yes} is passed.
 */
public final class JdkUpdateCommand implements CliCommand {

    @Override
    public String name() {
        return "update";
    }

    @Override
    public List<String> aliases() {
        return List.of("upgrade");
    }

    @Override
    public String description() {
        return "Update a Java Development Kit";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                CommonOpts.jdksDir(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url")
                        .hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to update\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    /** A planned update: the installed JDK being superseded and the feed entry replacing it. */
    private record Update(JdkHit old, JdkCatalog.Entry target) {}

    private @Nullable String spec;
    private boolean assumeYes;
    private @Nullable Path jdksDir;
    private @Nullable URI feedUrl;
    private @Nullable Path cacheFile;

    @Override
    public int run(Invocation in) throws Exception {
        this.spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.assumeYes = in.isSet("yes");
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(CliPaths::abs).orElse(null);

        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        // Reclaim any partial archive left by a previously canceled download.
        JdkInstaller.sweepStaleDownloads(registry.jdksRoot());

        // Keyword specs → resolve to the major of the best installed match so
        // managedHits() can filter by that major (e.g. "lts" → "21").
        if (JdkKeywords.isKeyword(spec)) {
            var kw = JdkKeywords.bestInstalledMatch(spec, registry.managedHits(null));
            spec = kw.map(h -> {
                        Integer m = JdkKeywords.leadingMajor(h.version());
                        return m != null ? String.valueOf(m) : JdkRegistry.identifierFor(h.home());
                    })
                    .orElse(spec);
        }
        List<JdkHit> managed = registry.managedHits(spec);
        if (managed.isEmpty()) {
            String msg = spec == null || spec.isBlank()
                    ? "Nothing to do. No JumpKick-managed JDKs installed."
                    : "Nothing to do. No JumpKick-managed JDK matches `" + spec + "`.";
            CommandWedge.printOk("JDK", msg);
            return 0;
        }

        if (!hostSupported()) return 1;
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        JdkCatalog catalog = fetchCatalog();

        // Build the plan.
        List<Update> updates = new ArrayList<>();
        List<String> noTarget = new ArrayList<>();
        for (JdkHit hit : managed) {
            String id = JdkRegistry.identifierFor(hit.home());
            Optional<JdkCatalog.Entry> target = latestPointRelease(catalog, id, os, arch);
            if (target.isEmpty()) {
                noTarget.add(id);
                continue;
            }
            JdkCatalog.Entry e = target.get();
            if (newerThan(e.version(), hit.version())) {
                updates.add(new Update(hit, e));
            }
        }

        for (String id : noTarget) {
            CliOutput.out(Theme.colorize("•", Theme.active().darkGray())
                    + " "
                    + Theme.colorize(id, Theme.active().cyan())
                    + Theme.colorize(
                            " — no update available in the feed", Theme.active().normalGray()));
        }

        if (updates.isEmpty()) {
            CommandWedge.printOk("JDK", "Nothing to do. All JumpKick-managed JDKs are up to date.");
            return 0;
        }

        if (!assumeYes && !confirm(updates)) {
            CommandWedge.printOk("JDK", "Aborted.");
            return 0;
        }

        // Asked here, before any download, so the whole update runs unattended once answered —
        // rather than stopping for a question after the user has waited through a fetch.
        boolean removeOld = assumeYes || confirmRemoveSuperseded(updates);

        return apply(registry, updates, removeOld) ? 0 : 1;
    }

    // --- apply --------------------------------------------------------------

    private boolean apply(JdkRegistry registry, List<Update> updates, boolean removeOld) {
        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        JdkInventory defaults = JdkInventory.of(registry.jdksRoot());
        Optional<String> currentDefault = defaults.defaultId();
        Optional<String> graalDefault = defaults.graalId();

        Map<String, InstalledJdk> built = new HashMap<>(); // dedupe installs by target folder
        List<String> superseded = new ArrayList<>(); // identifiers queued for removal, for pointer healing
        int updated = 0;
        int failed = 0;
        for (Update u : updates) {
            String oldId = JdkRegistry.identifierFor(u.old.home());
            try {
                InstalledJdk newJdk = built.get(u.target.installFolderName());
                if (newJdk == null) {
                    newJdk = installEntry(installer, u.target);
                    built.put(u.target.installFolderName(), newJdk);
                }
                // Repoint the stable <vendor>-<major> handle at the new patch
                // BEFORE the old one is GC'd, so an IntelliJ SDK pinned to the
                // stable path never dangles. (install() refreshes it too, but
                // the alreadyInstalled fast path can skip that.)
                repointStablePointer(registry, newJdk);
                if (removeOld && !oldId.equals(newJdk.identifier())) {
                    // Defer-delete the superseded patch: a running JVM may still
                    // hold it open (Windows can't unlink an in-use dir).
                    new JdkGarbage(registry.jdksRoot()).enqueue(IntellijJdkDir.installDirOf(u.old.home()));
                    superseded.add(oldId);
                }
                if (currentDefault.isPresent() && currentDefault.get().equals(oldId)) {
                    defaults.setDefault(newJdk);
                    currentDefault = Optional.of(newJdk.identifier());
                }
                if (graalDefault.isPresent() && graalDefault.get().equals(oldId)) {
                    defaults.setGraal(newJdk);
                    graalDefault = Optional.of(newJdk.identifier());
                }
                if (!oldId.equals(newJdk.identifier())) {
                    defaults.remove(oldId);
                }
                // Progress detail (one line per JDK); the command settles with a wedge below.
                CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().completedStep())
                        + " "
                        + Theme.colorize(oldId, Theme.active().warning())
                        + " "
                        + Theme.colorize("→", Theme.active().darkGray())
                        + " "
                        + Theme.colorize(newJdk.identifier(), Theme.active().focused()));
                updated++;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                CliOutput.out(Theme.colorize(Glyphs.CROSS, Theme.active().error())
                        + " "
                        + Theme.colorize(oldId, Theme.active().warning())
                        + ": "
                        + e.getMessage());
                failed++;
            }
        }

        // Reap what this command queued. Draining lives here, in the explicit verb where the user
        // was asked — never on the provisioning path, where it once fired during an ordinary build
        // and took the JDK that build was running on.
        if (removeOld) {
            new JdkGarbage(registry.jdksRoot()).drain();
            StableJdkPointer.healAfterRemovals(
                    registry,
                    superseded,
                    m -> CliOutput.out(
                            Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + m));
        }

        if (failed == 0) {
            String msg =
                    updated == 1 ? "Updated 1 JumpKick-managed JDK." : "Updated " + updated + " JumpKick-managed JDKs.";
            CommandWedge.printOk("JDK", msg);
        } else {
            CommandWedge.printFail("JDK", updated + " updated, " + failed + " failed.");
        }
        return failed == 0;
    }

    /**
     * Idempotently repoint {@code <vendor>-<major> → <install dir>} for a freshly-built JDK. Derives
     * the names from the install identifier ({@code temurin-25.0.4} → pointer {@code temurin-25}).
     */
    private static void repointStablePointer(JdkRegistry registry, InstalledJdk jdk) {
        String pointer = StableJdkPointer.pointerNameFor(jdk.identifier()).orElse(null);
        if (pointer == null) return;
        try {
            new StableJdkPointer(registry.jdksRoot()).ensure(pointer, IntellijJdkDir.installDirOf(jdk.home()));
        } catch (IOException ignored) {
            // Pointer is a convenience; the update itself already succeeded.
        }
    }

    /** Download + extract {@code entry} with a progress bar; journal the install. */
    private InstalledJdk installEntry(JdkInstaller installer, JdkCatalog.Entry entry)
            throws IOException, InterruptedException {
        InstalledJdk already = installer.alreadyInstalled(entry);
        if (already != null) return already;

        String label = JdkService.displayLabel(entry);
        long total = entry.archiveSize();
        InstalledJdk installed;
        try (JdkDownloadBar pb = JdkDownloadBar.show(CliOutput.stdout(), label)) {
            installed = installer.install(entry, bytes -> pb.update(bytes, total));
            pb.finish();
        }
        return installed;
    }

    // --- planning helpers ---------------------------------------------------

    /**
     * Highest-versioned non-preview catalog entry on this host that belongs to the same family as the
     * installed id (its {@code suggested_sdk_name} is a delimiter-bounded prefix of the id). The
     * suggested name encodes the major, so this never crosses majors.
     */
    private static Optional<JdkCatalog.Entry> latestPointRelease(
            JdkCatalog catalog, String installedId, String os, String arch) {
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

    /** {@code a > b} by {@link JdkSelector#versionKey} ordering ({@code 25.0.10 > 25.0.9}). */
    private static boolean newerThan(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        return JdkSelector.versionKey(a).compareTo(JdkSelector.versionKey(b)) > 0;
    }

    // --- confirmation -------------------------------------------------------

    private boolean confirm(List<Update> updates) {
        CliOutput.out(
                "\nThe following " + updates.size() + " JDK" + (updates.size() == 1 ? "" : "s") + " will be updated:");
        for (Update u : updates) {
            CliOutput.out("   "
                    + Theme.colorize(
                            JdkRegistry.identifierFor(u.old.home()),
                            Theme.active().warning())
                    + " "
                    + Theme.colorize("→", Theme.active().darkGray())
                    + " "
                    + Theme.colorize(
                            u.target.installFolderName(), Theme.active().focused()));
        }
        return Confirm.of(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " Proceed?", true)
                .ask();
    }

    /**
     * Ask whether the superseded installs should come off disk. Defaults to yes — that was the old
     * unconditional behaviour and it is what most people want — but it is a question now, because a
     * JDK is minutes of download and an IDE, a shell, or another project's lockfile may be pinned to
     * the exact patch directory. Declining keeps the old tree; the stable pointer moves either way.
     */
    private boolean confirmRemoveSuperseded(List<Update> updates) {
        List<String> victims = updates.stream()
                .map(u -> JdkRegistry.identifierFor(u.old.home()))
                .distinct()
                .toList();
        CliOutput.out("");
        CliOutput.out("  Also remove the superseded " + (victims.size() == 1 ? "install" : "installs") + ":");
        for (String v : victims) {
            CliOutput.out("    " + Theme.colorize(v, Theme.active().warning()));
        }
        return Confirm.of(
                        Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " Remove the old "
                                + (victims.size() == 1 ? "version" : "versions") + "?",
                        true)
                .ask();
    }

    // --- shared mechanics (mirrors JdkEnsureCommand) ------------------------

    private boolean hostSupported() {
        if (HostPlatform.supported()) return true;
        CommandWedge.printFail(
                "JDK",
                "host " + Os.name()
                        + "/"
                        + System.getProperty("os.arch")
                        + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
        return false;
    }

    private JdkCatalog fetchCatalog() throws IOException, InterruptedException {
        // Delegate to an already-running engine when there is one; never start one — `jk jdk
        // update` must work standalone with no engine running and no JDK installed yet. Skipped
        // for a custom feed with no explicit --cache-file: that combination falls back to a
        // throwaway ephemeralCachePath() below, which the engine has no way to share with this
        // process.
        if (feedUrl == null || cacheFile != null) {
            EngineCatalogFreshen.freshenCatalogIfRunning(
                    EnginePaths.current(), "jdks", feedUrl != null ? feedUrl.toString() : null, cacheFile);
        }
        boolean refresh = SessionContext.current().config().forceOr(false);
        JdkCatalogClient client = (feedUrl != null
                        ? new JdkCatalogClient(
                                new Http(),
                                feedUrl,
                                cacheFile != null ? cacheFile : ephemeralCachePath(),
                                Duration.ZERO)
                        : new JdkCatalogClient())
                .onWarning(CliOutput.stderr()::println);
        return client.fetch(refresh);
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        Files.delete(tmp); // force a fresh fetch
        return tmp;
    }
}
