// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
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

/**
 * Resolves/fetches the Groovy runtime closure for the target Groovy version (Maven via {@link
 * PubGrubResolver}), CAS-cached under {@code tools/groovy/}. The same closure serves both the
 * groovy-compiler worker's classpath and (wave 2) runtime injection onto the app classpath.
 */
public final class GroovyToolResolver {

    /** The single root coordinate; its POM drags in the Groovy runtime closure. */
    public static final String GROOVY_MODULE = "org.apache.groovy:groovy";

    private GroovyToolResolver() {}

    /**
     * Resolve and fetch the Groovy closure ({@code groovy} + transitives) for {@code groovyVersion},
     * returning the local jar paths (in the CAS) for the plugin's classpath.
     *
     * @param repos the repositories to resolve against (build via {@link RepoGroupBuilder#buildFor},
     *     so project mirrors / credentials apply)
     * @param cas the content-addressed store the jars land in
     * @param groovyVersion the exact Groovy version to match (e.g. {@code 5.0.4})
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        requireSupportedVersion(groovyVersion);
        Path cacheFile = cacheFile(cas, groovyVersion);
        boolean refresh = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        Dependency root = new Dependency(GROOVY_MODULE, VersionSelector.parse("=" + groovyVersion));
        Resolution resolution = new PubGrubResolver(repos).resolve(List.of(root));

        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            Coordinate coord = mod.coordinate();
            // POM-only modules (BOMs/parents) carry no jar — tryFetchArtifact
            // returns empty and we simply omit them from the classpath.
            var hit = repos.tryFetchArtifact(coord);
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException("Groovy closure for "
                    + groovyVersion
                    + " resolved to no jars — is "
                    + GROOVY_MODULE
                    + ":"
                    + groovyVersion
                    + " available in the configured repositories?");
        }
        writeCachedClosure(cacheFile, shas);
        return jars;
    }

    /**
     * The runtime-injection closure for {@code groovyVersion}: the {@code groovy} jar plus its
     * transitive closure — the seam wave 2 uses to put Groovy on the app's runtime classpath. Same
     * resolution (and cache) as {@link #resolveClasspath}.
     */
    public static List<Path> resolveRuntime(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        return resolveClasspath(repos, cas, groovyVersion);
    }

    /**
     * Resolve (and fetch into the CAS) the version-matched single {@code groovy} jar. It must go on
     * the <em>compilation</em> classpath: user Groovy code compiles against the Groovy runtime
     * types. Already in the CAS after {@link #resolveClasspath} (the jar roots the closure), so this
     * is a cache hit, not a download.
     */
    public static Path resolveGroovyJar(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        Coordinate coord = Coordinate.of("org.apache.groovy", "groovy", groovyVersion);
        var hit = repos.tryFetchArtifact(coord);
        if (hit.isEmpty()) {
            throw new IOException(GROOVY_MODULE + ":" + groovyVersion + " not found in the configured repositories");
        }
        return hit.get().fetched().cachePath();
    }

    /** Guard the 5.0 floor: the groovy-compiler worker drives the Groovy 5 compiler APIs. */
    static void requireSupportedVersion(String version) {
        int major = 0;
        String[] parts = version.split("[.-]");
        if (parts.length > 0) major = parseLeadingInt(parts[0]);
        if (major < 5) {
            throw new IllegalArgumentException("jk requires Groovy 5.0 or newer, but the project targets "
                    + version
                    + ". Pin a newer version in jk.toml (project.groovy).");
        }
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
    }

    private static Path cacheFile(Cas cas, String groovyVersion) {
        return cas.root()
                .resolve("tools")
                .resolve("groovy")
                .resolve(groovyVersion)
                .resolve("closure.shas");
    }

    /**
     * Reconstruct a previously resolved closure from its recorded hashes, or {@code null} if there's
     * no record or any blob has been evicted from the CAS (forcing a fresh resolve).
     */
    static List<Path> readCachedClosure(Path cacheFile, Cas cas) throws IOException {
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
