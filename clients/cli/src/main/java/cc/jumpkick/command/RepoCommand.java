// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk repo} — artifact store (store CAS + Maven-layout {@code repos/} mirrors), offline
 * coordinate search, and repository credentials. Distinct from {@code jk cache} (cache tier:
 * rebuildable action outputs under {@code JK_CACHE_DIR}).
 */
public final class RepoCommand extends GroupCommand {

    @Override
    public String name() {
        return "repo";
    }

    @Override
    public String description() {
        return "Manage the artifact store (deps CAS, repos, credentials)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new RepoStorageCommand(),
                new RepoSearchCommand(),
                new RepoRefreshCommand(),
                new RepoPruneCommand(),
                new RepoLoginCommand(),
                new RepoLogoutCommand());
    }

    /**
     * {@code jk repo refresh <coordinate>} — drop a coordinate's mirror entries so the next resolve
     * re-fetches it.
     *
     * <p>jk's mirror is first-write-wins: a stored coordinate keeps serving the bytes it was first
     * fetched with, which matches Maven Central's immutability contract. This is the escape hatch
     * for the case where upstream genuinely republished different bytes (JK-1460; see
     * {@code docs/mirror-verification-decision.md}).
     */
    public static final class RepoRefreshCommand implements CliCommand {
        @Override
        public String name() {
            return "refresh";
        }

        @Override
        public String description() {
            return "Evict a coordinate from the local mirror so it re-fetches";
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    Param.of("coordinate", Arity.ONE_OR_MORE, "One or more group:artifact:version coordinates."));
        }

        @Override
        public int run(Invocation in) {
            Path cacheRoot = CacheCommand.resolveCacheRoot(
                    in.value("cache-dir").map(Path::of).orElse(null));
            Path reposRoot = JkStores.storeRootFor(cacheRoot).resolve("repos");
            List<String> repoNames = repoNames(reposRoot);
            int evicted = 0;
            int missed = 0;
            for (String spec : in.positionals()) {
                cc.jumpkick.model.Coordinate coord;
                try {
                    coord = cc.jumpkick.model.Coordinate.parse(spec);
                } catch (IllegalArgumentException e) {
                    CliOutput.err(e.getMessage());
                    return 2;
                }
                String relPath = cc.jumpkick.repo.MavenLayout.artifactPath(coord);
                List<String> hitRepos = new ArrayList<>();
                for (String repo : repoNames) {
                    if (RepoArtifactStore.forRepoName(cacheRoot, repo).evict(relPath)) hitRepos.add(repo);
                }
                if (hitRepos.isEmpty()) {
                    missed++;
                    CliOutput.out("not mirrored: " + Coords.gav(coord));
                } else {
                    evicted++;
                    CliOutput.out("evicted " + Coords.gav(coord) + " from " + String.join(", ", hitRepos));
                }
            }
            if (evicted > 0) {
                CliOutput.out("");
                CliOutput.out("Re-fetches on the next resolve (`jk lock` or a build).");
            }
            // Nothing evicted at all is a soft failure: the user named something jk does not hold.
            return evicted == 0 && missed > 0 ? 1 : 0;
        }

        /** Named mirror directories under {@code store/repos/}, or the well-known set if unlistable. */
        private static List<String> repoNames(Path reposRoot) {
            if (!Files.isDirectory(reposRoot)) return List.of("central", "local");
            try (var s = Files.list(reposRoot)) {
                List<String> names = s.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
                return names.isEmpty() ? List.of("central", "local") : names;
            } catch (IOException e) {
                return List.of("central", "local");
            }
        }
    }

    /**
     * {@code jk repo prune} — store-side reclamation: sweep unreferenced CAS blobs, drop leftover
     * CAS temp files, and expire old run logs. Garbage-only — reachable blobs are never evicted,
     * even over the display budget. Engine-hosted at an idle boundary, like {@code jk cache prune}.
     */
    public static final class RepoPruneCommand implements CliCommand {
        @Override
        public String name() {
            return "prune";
        }

        @Override
        public String description() {
            return "Sweep unreferenced CAS blobs and expired run logs";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            cc.jumpkick.cli.GlobalOptions global = cc.jumpkick.cli.GlobalOptions.from(in);
            Path root = CacheCommand.resolveCacheRoot(cacheDir);

            // Counts settle from the terminal plan-finish before the console listener renders.
            var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
            cc.jumpkick.cli.run.ConsoleSpec spec = sweepSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            cc.jumpkick.cli.run.BuildPlanConsole.Mode mode = cc.jumpkick.cli.run.BuildPlanConsole.modeFor(global);
            cc.jumpkick.run.BuildPlanResult result;
            try {
                // olderThanDays = MAX_VALUE: a pre-"sweep" engine falls back to its prune plan;
                // the huge cutoff keeps action entries untouched while sweep=true still runs the GC.
                result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                                "sweep", root, Integer.MAX_VALUE, dryRun, true, false),
                        steps -> cc.jumpkick.cli.run.BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Repo"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Repo", e.getMessage()));
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            if (summary[0] != null) {
                CacheCommand.CachePruneCommand.warnReachableEvicted(summary[0].reachableEvicted());
            }
            return result.success() ? 0 : 1;
        }

        /** The Repo chip spec; counts are read lazily, at result-line render time. */
        static cc.jumpkick.cli.run.ConsoleSpec sweepSpec(
                boolean dryRun, java.util.function.LongSupplier files, java.util.function.LongSupplier bytes) {
            return new cc.jumpkick.cli.run.ConsoleSpec(
                    "Repo",
                    r -> {
                        long f = Math.max(0, files.getAsLong());
                        long b = Math.max(0, bytes.getAsLong());
                        if (dryRun) {
                            if (f == 0) return "Dry run: nothing to sweep.";
                            return "Dry run: would remove "
                                    + f + " " + (f == 1 ? "file" : "files")
                                    + ", " + CacheCommand.fmtBytes(b) + " reclaimable.";
                        }
                        if (f == 0) return "Finished sweeping store. Nothing to clean up.";
                        return "Finished sweeping store. "
                                + f + " " + (f == 1 ? "file" : "files")
                                + " removed, " + CacheCommand.fmtBytes(b) + " reclaimed.";
                    },
                    r -> "Failed to sweep the store.",
                    true);
        }
    }

    /**
     * {@code jk repo storage} — artifact store: store CAS + {@code repos/} mirrors + run logs (not
     * the cache tier).
     */
    public static final class RepoStorageCommand implements CliCommand {
        @Override
        public String name() {
            return "storage";
        }

        @Override
        public String description() {
            return "Show artifact-store size and utilization";
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheRoot = CacheCommand.resolveCacheRoot(
                    in.value("cache-dir").map(Path::of).orElse(null));
            Path storeRoot = JkStores.storeRootFor(cacheRoot);
            if (!Files.isDirectory(cacheRoot) && !Files.isDirectory(storeRoot)) {
                CliOutput.out(
                        "Store directory: " + cc.jumpkick.cli.PathDisplay.styledRaw(storeRoot) + " (not yet created)");
                return 0;
            }
            CacheCommand.SectionStats s = CacheCommand.sectionStats(cacheRoot);
            var cfg = cc.jumpkick.config.JkCacheConfig.resolve();
            long maxBytes = cfg.maxStoreSizeBytes();
            // Last-pruned stamp still lives under the cache root (prune job).
            String lastPruned = CacheCommand.lastPrunedLabel(cacheRoot);
            for (String line : CacheCommand.renderRepoStorageTable(
                    s.cas(), s.repos(), s.runs(), s.repoFiles(), s.repoBytes(), maxBytes, lastPruned)) {
                CliOutput.out(line);
            }
            return 0;
        }
    }

    /**
     * {@code jk repo search} — search locally-mirrored coordinates by group/artifact substring
     * (formerly {@code jk cache search}).
     */
    public static final class RepoSearchCommand implements CliCommand {
        @Override
        public String name() {
            return "search";
        }

        @Override
        public String description() {
            return "Search locally-cached artifacts by group/artifact substring";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<N>", "Cap coordinates shown (default: no cap)", "--limit"),
                    cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of(
                    "term", Arity.ONE_OR_MORE, "One or more substrings. All must match (in group or artifact)."));
        }

        @Override
        public int run(Invocation in) {
            List<String> terms = in.positionals();
            Integer limit = in.value("limit").map(Integer::parseInt).orElse(null);
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            Path cacheRoot = CacheCommand.resolveCacheRoot(cacheDir);
            List<String> lowerTerms =
                    terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            List<RepoArtifactStore.Module> hits = RepoArtifactStore.allModules(cacheRoot).stream()
                    .filter(m -> allMatch(
                            lowerTerms,
                            m.group().toLowerCase(Locale.ROOT),
                            m.artifact().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(RepoArtifactStore.Module::moduleKey))
                    .toList();
            if (hits.isEmpty()) {
                CliOutput.out("No cached coordinates match: " + String.join(" ", terms));
                return 1;
            }
            int total = hits.size();
            int shown = limit != null && limit > 0 && total > limit ? limit : total;
            int keyWidth = 0;
            for (int i = 0; i < shown; i++)
                keyWidth = Math.max(keyWidth, hits.get(i).moduleKey().length());
            long versionCount = 0;
            for (int i = 0; i < shown; i++) {
                RepoArtifactStore.Module m = hits.get(i);
                List<String> versions = new ArrayList<>(m.versions());
                versions.sort((a, b) -> Versions.compare(b, a));
                versionCount += versions.size();
                String key = m.moduleKey();
                String gap = " ".repeat(Math.max(0, keyWidth - key.length()));
                CliOutput.out(Coords.module(key)
                        + gap
                        + "  "
                        + String.join(
                                ", ", versions.stream().map(Coords::version).toList()));
            }
            if (shown < total) {
                Theme st = Theme.active();
                CliOutput.out(Theme.colorize("…", st.darkGray())
                        + " "
                        + Theme.colorize("and ", st.normalGray())
                        + Theme.colorize(String.valueOf(total - shown), st.focused())
                        + " "
                        + Theme.colorize("more", st.normalGray())
                        + " "
                        + Theme.colorize("(pass --limit " + total + " or refine the search)", st.dim()));
            }
            {
                Theme st = Theme.active();
                CliOutput.out(Theme.colorize(CacheCommand.fmtCount(shown), st.focused())
                        + " "
                        + Theme.colorize("coordinate" + (shown == 1 ? "" : "s"), st.settled())
                        + ", "
                        + Theme.colorize(CacheCommand.fmtCount(versionCount), st.focused())
                        + " "
                        + Theme.colorize("version" + (versionCount == 1 ? "" : "s") + " cached", st.settled()));
            }
            return 0;
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
    }
}
