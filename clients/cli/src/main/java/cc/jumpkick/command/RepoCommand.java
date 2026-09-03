// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk repo} — offline coordinate search, mirror refresh, and repository credentials. Store
 * size / clean / nuke live under {@code jk storage} (peer of {@code jk cache}).
 */
public final class RepoCommand extends GroupCommand {

    @Override
    public String name() {
        return "repo";
    }

    @Override
    public String description() {
        return "Search mirrors and manage repository credentials";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new RepoSearchCommand(), new RepoRefreshCommand(), new RepoLoginCommand(), new RepoLogoutCommand());
    }

    /**
     * {@code jk repo refresh <coordinate>} — drop a coordinate's mirror entries so the next resolve
     * re-fetches it.
     *
     * <p>jk's mirror is first-write-wins: a stored coordinate keeps serving the bytes it was first
     * fetched with, which matches Maven Central's immutability contract. This is the escape hatch
     * for the rare case where upstream genuinely republished different bytes.
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
            return List.of(CommonOpts.cacheDir());
        }

        @Override
        public List<Param> parameters() {
            return List.of(
                    Param.of("coordinate", Arity.ONE_OR_MORE, "One or more group:artifact:version coordinates."));
        }

        @Override
        public int run(Invocation in) {
            Path cacheRoot = CacheCommand.resolveCacheRoot(
                    in.value("cache-dir").map(CliPaths::abs).orElse(null));
            CacheInventoryAck ack;
            try {
                ack = EngineClient.cacheInventory(
                        EnginePaths.current(),
                        "repo-refresh",
                        cacheRoot,
                        JkStores.store(),
                        List.of(),
                        in.positionals(),
                        false);
            } catch (IOException e) {
                CliOutput.err(String.valueOf(e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (ack.error() != null) {
                CliOutput.err(ack.error());
                return Exit.FAILURE;
            }
            for (String packed : ack.lines()) {
                String[] f = packed.split("\\|", -1);
                String group = f.length > 0 ? f[0] : "";
                String artifact = f.length > 1 ? f[1] : "";
                String version = f.length > 2 ? f[2] : "";
                String repos = f.length > 3 ? f[3] : "";
                if (repos.isEmpty()) {
                    CliOutput.out("not mirrored: " + Coords.gav(group, artifact, version));
                } else {
                    CliOutput.out("evicted " + Coords.gav(group, artifact, version) + " from " + repos);
                }
            }
            if (ack.evicted() > 0) {
                CliOutput.out("");
                CliOutput.out("Re-fetches on the next resolve (`jk lock` or a build).");
            }
            return ack.evicted() == 0 && ack.missed() > 0 ? 1 : 0;
        }
    }

    /**
     * {@code jk repo search} — search locally-mirrored coordinates by group/artifact substring.
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
                    Opt.value("<N>", "Cap coordinates shown (default: no cap)", "--limit"), CommonOpts.cacheDir());
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
            Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
            Path cacheRoot = CacheCommand.resolveCacheRoot(cacheDir);
            CacheInventoryAck ack;
            try {
                ack = EngineClient.cacheInventory(
                        EnginePaths.current(), "repo-search", cacheRoot, JkStores.store(), terms, List.of(), false);
            } catch (IOException e) {
                CliOutput.err(String.valueOf(e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (ack.error() != null) {
                CliOutput.err(ack.error());
                return Exit.FAILURE;
            }
            List<String> hits = ack.entries();
            if (hits.isEmpty()) {
                CliOutput.out("No cached coordinates match: " + String.join(" ", terms));
                return 1;
            }
            int total = hits.size();
            int shown = limit != null && limit > 0 && total > limit ? limit : total;
            int keyWidth = 0;
            List<String> keys = new ArrayList<>();
            List<List<String>> versions = new ArrayList<>();
            for (String packed : hits) {
                String[] f = packed.split("\\|", -1);
                String key = (f.length > 0 ? f[0] : "") + ":" + (f.length > 1 ? f[1] : "");
                keys.add(key);
                List<String> vers = f.length > 2 && !f[2].isEmpty() ? List.of(f[2].split(",", -1)) : List.of();
                versions.add(vers);
            }
            for (int i = 0; i < shown; i++)
                keyWidth = Math.max(keyWidth, keys.get(i).length());
            long versionCount = 0;
            for (int i = 0; i < shown; i++) {
                versionCount += versions.get(i).size();
                String key = keys.get(i);
                String gap = " ".repeat(Math.max(0, keyWidth - key.length()));
                CliOutput.out(Coords.module(key)
                        + gap
                        + "  "
                        + String.join(
                                ", ",
                                versions.get(i).stream().map(Coords::version).toList()));
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
    }
}
