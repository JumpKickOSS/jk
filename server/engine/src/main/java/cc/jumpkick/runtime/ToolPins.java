// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.Interpolation;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The {@code [[tool]]} rows {@code jk lock} writes: every tool the active plugins fetch, pinned by digest. */
final class ToolPins {

    private ToolPins() {}

    /**
     * Pin every plugin tool the root or a member fetches as one Maven artifact — the step, command
     * and packager lanes — or as a file at a URL, by the sha256 of its bytes, and every tool it
     * fetches as a runtime closure by a closure row naming each jar, each jar a row of its own. A
     * tool whose coordinate follows the host is pinned for each {@link Interpolation.Host#LOCKED} platform that publishes it, so a machine on
     * another OS verifies its own download against the lock; the running host's artifact must
     * exist. A plain {@code jk lock} keeps a floating tool's locked version and reuses the digests
     * the previous lock pins for the same artifact instead of downloading it again.
     */
    static Lockfile pin(
            Lockfile lock,
            @Nullable Lockfile previous,
            Path lockDir,
            Map<Path, JkBuild> modules,
            @Nullable URI repoUrl,
            LockPipeline.Progress progress) {
        Cas cas = JkStores.storeCas();
        // coordinate -> must exist (it is this host's); first module that asks owns the repositories
        Map<Coordinate, Boolean> wanted = new LinkedHashMap<>();
        Map<Coordinate, RepoGroup> reposFor = new LinkedHashMap<>();
        // url -> must be fetched (it is this host's)
        Map<String, Boolean> urls = new LinkedHashMap<>();
        Set<ToolEntry> closures = new LinkedHashSet<>();
        Map<Coordinate, RepoGroup> closureJars = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> module : modules.entrySet()) {
            Path dir = module.getKey();
            JkBuild build = module.getValue();
            Lockfile view = dir.equals(lockDir)
                    ? lock
                    : lock.forMember(lockDir.relativize(dir).toString().replace('\\', '/'));
            RepoGroup repos = null;
            for (PluginContributions.LockedTool tool :
                    PluginContributions.lockedArtifacts(build, dir, view.platformPins())) {
                if (repos == null) repos = RepoGroupBuilder.buildFor(build, repoUrl, cas, BuildEnv.forModule(dir));
                Coordinate coord = toolCoordinate(tool.id(), previous, repos);
                wanted.merge(coord, tool.host(), Boolean::logicalOr);
                reposFor.putIfAbsent(coord, repos);
            }
            for (PluginContributions.LockedTool url : PluginContributions.lockedUrls(build, dir, view.platformPins())) {
                urls.merge(url.id(), url.host(), Boolean::logicalOr);
            }
            for (PluginContributions.StepDep tool : PluginContributions.closureTools(build, dir, view.platformPins())) {
                if (repos == null) repos = RepoGroupBuilder.buildFor(build, repoUrl, cas, BuildEnv.forModule(dir));
                ToolEntry row = closureRow(tool, previous, repos, progress);
                closures.add(row);
                for (String jar : row.closure()) closureJars.putIfAbsent(Coordinate.parse(jar), repos);
            }
        }
        if (wanted.isEmpty() && urls.isEmpty() && closures.isEmpty()) return lock.withTools(List.of());
        progress.label("lock tools");
        // group:artifact:version!type -> classifier -> hex
        Map<String, Map<@Nullable String, String>> digests = new LinkedHashMap<>();
        Map<String, Coordinate> groupHead = new LinkedHashMap<>();
        for (Map.Entry<Coordinate, Boolean> e : wanted.entrySet()) {
            Coordinate coord = e.getKey();
            String hex = toolDigest(coord, e.getValue(), previous, Objects.requireNonNull(reposFor.get(coord)));
            if (hex == null) continue; // not published for that platform
            String group = coord.toGav() + "!" + coord.type();
            groupHead.putIfAbsent(group, coord);
            digests.computeIfAbsent(group, k -> new LinkedHashMap<>()).put(coord.classifier(), hex);
        }
        List<ToolEntry> rows = new ArrayList<>();
        for (Map.Entry<String, Map<@Nullable String, String>> g : digests.entrySet()) {
            Coordinate head = Objects.requireNonNull(groupHead.get(g.getKey()));
            Map<String, String> perPlatform = new LinkedHashMap<>();
            for (Map.Entry<@Nullable String, String> d : g.getValue().entrySet()) {
                String classifier = d.getKey();
                Coordinate one = new Coordinate(head.group(), head.artifact(), head.version(), classifier, head.type());
                if (classifier == null || g.getValue().size() == 1) {
                    rows.add(ToolEntry.single(one, d.getValue()));
                } else {
                    perPlatform.put(classifier, d.getValue());
                }
            }
            if (!perPlatform.isEmpty()) {
                rows.add(new ToolEntry(head.module(), head.version(), head.type(), null, null, perPlatform));
            }
        }
        for (Map.Entry<String, Boolean> url : urls.entrySet()) {
            String hex = urlDigest(url.getKey(), url.getValue(), previous, cas);
            if (hex != null) rows.add(ToolEntry.atUrl(url.getKey(), hex));
        }
        rows.addAll(closures);
        for (Map.Entry<Coordinate, RepoGroup> jar : closureJars.entrySet()) {
            Coordinate coord = jar.getKey();
            if (rows.stream().anyMatch(row -> row.pins(coord))) continue;
            rows.add(
                    ToolEntry.single(coord, Objects.requireNonNull(toolDigest(coord, true, previous, jar.getValue()))));
        }
        return lock.withTools(rows);
    }

    /**
     * The closure row for {@code tool}: the previous lock's when a plain {@code jk lock} keeps one
     * for the same roots, else the closure resolved from the tool's POMs.
     */
    private static ToolEntry closureRow(
            PluginContributions.StepDep tool,
            @Nullable Lockfile previous,
            RepoGroup repos,
            LockPipeline.Progress progress) {
        String spec = Objects.requireNonNull(tool.coordinateSpec());
        ToolEntry kept = previous == null
                ? null
                : ToolEntry.closureIn(previous.tools(), spec, tool.with(), tool.managedBySpec());
        if (kept != null) return kept;
        progress.label("lock " + spec);
        try {
            ToolClosures.Resolved closure = ToolClosures.resolve(tool, repos);
            return ToolEntry.closureOf(closure.root(), closure.with(), closure.managedBy(), closure.jars());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("tool " + spec + " — " + e.getMessage(), e);
        }
    }

    /**
     * The sha256 hex of the file at {@code url}: the previous lock's pin when it has one, else the
     * download's. Null for another platform's URL that does not answer: a build on that platform
     * then fails naming the missing row. This host's URL is {@code required}.
     */
    private static @Nullable String urlDigest(String url, boolean required, @Nullable Lockfile previous, Cas cas) {
        String kept = previous == null ? null : ToolEntry.urlSha256In(previous.tools(), url);
        if (kept != null) return kept;
        try {
            return Hashing.sha256Hex(UrlTools.download(url, cas));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!required && e instanceof IOException) {
                Log.debug("urlDigest: " + url + " not pinned", e);
                return null;
            }
            throw new IllegalStateException("tool " + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * {@code spec} as a concrete coordinate: a bare version is exact; a floating one keeps the version
     * the previous lock pins for the tool, else resolves to the newest release it admits.
     */
    private static Coordinate toolCoordinate(String spec, @Nullable Lockfile previous, RepoGroup repos) {
        Coordinate raw = Coordinate.parse(spec);
        if (VersionSelector.parse(raw.version()) instanceof VersionSelector.Exact exact) {
            return new Coordinate(raw.group(), raw.artifact(), exact.version(), raw.classifier(), raw.type());
        }
        if (previous != null) {
            for (ToolEntry row : previous.tools()) {
                if (raw.module().equals(row.coordinate()) && row.type().equals(raw.type())) {
                    return new Coordinate(
                            raw.group(),
                            raw.artifact(),
                            Objects.requireNonNull(row.version()),
                            raw.classifier(),
                            raw.type());
                }
            }
        }
        try {
            return PluginBuild.resolveCoordinate(repos, spec);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("tool " + spec + " — " + e.getMessage(), e);
        }
    }

    /**
     * The sha256 hex of {@code coord}'s bytes: the previous lock's pin when it has one, else the
     * download's. Null for another platform's artifact the repositories do not serve (unpublished,
     * or out of reach offline): a build on that platform then fails naming the missing row. This
     * host's artifact is {@code required}.
     */
    private static @Nullable String toolDigest(
            Coordinate coord, boolean required, @Nullable Lockfile previous, RepoGroup repos) {
        ToolEntry kept = previous == null ? null : previous.toolRow(coord);
        if (kept != null) return kept.sha256Hex(coord.classifier());
        try {
            var fetched = repos.tryFetchArtifact(coord);
            if (fetched.isPresent()) return fetched.get().fetched().sha256();
            if (!required) return null;
            throw new IllegalStateException("tool " + coord + " not found in any repo");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!required && e instanceof IOException) {
                Log.debug("toolDigest: " + coord + " not pinned", e);
                return null;
            }
            throw new IllegalStateException("tool " + coord + " — " + e.getMessage(), e);
        }
    }
}
