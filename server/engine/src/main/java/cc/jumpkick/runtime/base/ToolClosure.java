// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import cc.jumpkick.resolver.Resolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Resolve and CAS-cache the jar closure of a compiler toolchain, for every language that forks one.
 *
 * <p>{@link KotlinBtaResolver} and {@link GroovyToolResolver} each carried this routine verbatim:
 * the same PubGrub resolve from a single root coordinate, the same POM-only skip, the same
 * hash-list cache file under {@code <cas>/tools/<tool>/<version>/closure.shas}, and the same
 * eviction rule (any missing blob invalidates the whole closure — a partial closure is unusable).
 * What is genuinely per language is the root coordinate, the supported-version floor and the
 * single extra jar each puts on the <em>compilation</em> classpath, and those stay in the two
 * resolvers.
 */
final class ToolClosure {

    private ToolClosure() {}

    /**
     * Resolve and fetch {@code module:version}'s closure, returning the local (CAS) jar paths.
     *
     * @param tool the cache directory name under {@code tools/}, e.g. {@code kotlin-bta}
     * @param label the human name in the empty-closure error, e.g. {@code Kotlin Build Tools}
     */
    static List<Path> resolve(RepoGroup repos, Cas cas, String tool, String label, String module, String version)
            throws IOException, InterruptedException {
        Path cacheFile = cacheFile(cas, tool, version);
        boolean refresh = SessionContext.current().config().forceOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        Dependency root = new Dependency(module, VersionSelector.parse("=" + version));
        Resolution resolution = new PubGrubResolver(repos).resolve(List.of(root));

        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            // POM-only modules (BOMs/parents) carry no jar — tryFetchArtifact
            // returns empty and we simply omit them from the classpath.
            var hit = repos.tryFetchArtifact(mod.coordinate());
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException(label + " closure for " + version + " resolved to no jars — is " + module + ":"
                    + version + " available in the configured repositories?");
        }
        writeCachedClosure(cacheFile, shas);
        return jars;
    }

    /**
     * Fetch one exact artifact into the CAS and return its path. Already in the CAS after
     * {@link #resolve} (both callers name a jar the closure roots or contains), so this is a cache
     * hit, not a download.
     */
    static Path single(RepoGroup repos, Coordinate coord) throws IOException, InterruptedException {
        var hit = repos.tryFetchArtifact(coord);
        if (hit.isEmpty()) {
            throw new IOException(coord.group() + ":" + coord.artifact() + ":" + coord.version()
                    + " not found in the configured repositories");
        }
        return hit.get().fetched().cachePath();
    }

    /**
     * The {@code index}-th dot/dash-separated part of {@code version} as its leading integer, or 0
     * when the part is absent or does not start with a digit. Enough to read a major/minor for a
     * supported-version floor; it is deliberately not version <em>comparison</em>, which has its
     * own owner in {@code cc.jumpkick.version.Versions}.
     */
    static int versionPart(String version, int index) {
        String[] parts = version.split("[.-]");
        if (index >= parts.length) return 0;
        String part = parts[index];
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
    }

    private static Path cacheFile(Cas cas, String tool, String version) {
        return cas.root().resolve("tools").resolve(tool).resolve(version).resolve("closure.shas");
    }

    /**
     * Reconstruct a previously resolved closure from its recorded hashes, or {@code null} if there's
     * no record or any blob has been evicted from the CAS (forcing a fresh resolve).
     */
    static @Nullable List<Path> readCachedClosure(Path cacheFile, Cas cas) throws IOException {
        if (!Files.isRegularFile(cacheFile)) return null;
        List<Path> jars = new ArrayList<>();
        for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
            String sha = line.strip();
            if (sha.isEmpty()) continue;
            if (!cas.contains(sha)) return null; // evicted → re-resolve
            jars.add(cas.pathFor(sha));
        }
        return jars.isEmpty() ? null : jars;
    }

    /** Record the closure's content hashes for a future warm build. */
    static void writeCachedClosure(Path cacheFile, List<String> shas) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, shas, StandardCharsets.UTF_8);
    }
}
