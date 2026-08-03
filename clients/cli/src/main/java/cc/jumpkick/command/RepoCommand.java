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
 * {@code jk repo} — local CAS / Maven mirrors, offline coordinate search, and artifact-repository
 * credentials ({@code ~/.jk/repo-credentials/}). Distinct from {@code jk cache} (action cache only).
 */
public final class RepoCommand extends GroupCommand {

    @Override
    public String name() {
        return "repo";
    }

    @Override
    public String description() {
        return "Manage artifact repos, local CAS, and credentials";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new RepoStorageCommand(),
                new RepoSearchCommand(),
                new RepoLoginCommand(),
                new RepoLogoutCommand());
    }

    /**
     * {@code jk repo storage} — CAS blobs, worker JAR mirrors, and run logs (not the action cache).
     */
    public static final class RepoStorageCommand implements CliCommand {
        @Override
        public String name() {
            return "storage";
        }

        @Override
        public String description() {
            return "Show local CAS / repo mirror size and utilization";
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
                CliOutput.out("Store directory: "
                        + cc.jumpkick.cli.PathDisplay.styledRaw(storeRoot)
                        + " (not yet created)");
                return 0;
            }
            CacheCommand.SectionStats s = CacheCommand.sectionStats(cacheRoot);
            var cfg = cc.jumpkick.config.JkCacheConfig.resolve();
            long maxBytes = cfg.storeMaxSizeBytes();
            // Last-pruned stamp still lives under the cache root (prune job).
            String lastPruned = CacheCommand.lastPrunedLabel(cacheRoot);
            for (String line : CacheCommand.renderRepoStorageTable(
                    s.cas(),
                    s.repos(),
                    s.runs(),
                    s.repoFiles(),
                    s.repoBytes(),
                    maxBytes,
                    lastPruned)) {
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
                    Opt.value("<N>", "Cap the number of coordinates displayed (default: no cap).", "--limit"),
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
