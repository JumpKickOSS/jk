// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import cc.jumpkick.resolver.Resolution;
import cc.jumpkick.scala.ScalaResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves/fetches the Scala 3 compiler + published sbt bridge (and transitives) into the CAS
 * under {@code tools/scala/}. That closure is extra worker classpath for mixed Java+Scala
 * Zinc sessions — not the project's compile classpath.
 */
public final class ScalaToolResolver {

    private ScalaToolResolver() {}

    /**
     * Resolve and fetch compiler + bridge for {@code scalaVersion}, returning local jar paths
     * for the java-compiler worker {@code -cp}.
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String scalaVersion)
            throws IOException, InterruptedException {
        requireSupportedVersion(scalaVersion);
        Path cacheFile = cacheFile(cas, scalaVersion);
        boolean refresh = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        List<Dependency> roots = List.of(
                new Dependency(ScalaResolver.COMPILER_MODULE, VersionSelector.parse("=" + scalaVersion)),
                new Dependency(ScalaResolver.BRIDGE_MODULE, VersionSelector.parse("=" + scalaVersion)));
        Resolution resolution = new PubGrubResolver(repos).resolve(roots);

        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            Coordinate coord = mod.coordinate();
            var hit = repos.tryFetchArtifact(coord);
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException("Scala compiler closure for "
                    + scalaVersion
                    + " resolved to no jars — is "
                    + ScalaResolver.COMPILER_MODULE
                    + ":"
                    + scalaVersion
                    + " available in the configured repositories?");
        }
        writeCachedClosure(cacheFile, shas);
        return jars;
    }

    /** Path of the version-matched {@code scala3-sbt-bridge} jar (already in the CAS after {@link #resolveClasspath}). */
    public static Path resolveBridgeJar(RepoGroup repos, Cas cas, String scalaVersion)
            throws IOException, InterruptedException {
        Coordinate coord = Coordinate.of("org.scala-lang", "scala3-sbt-bridge", scalaVersion);
        var hit = repos.tryFetchArtifact(coord);
        if (hit.isEmpty()) {
            throw new IOException(
                    ScalaResolver.BRIDGE_MODULE + ":" + scalaVersion + " not found in the configured repositories");
        }
        return hit.get().fetched().cachePath();
    }

    /** Scala 3 only: Zinc's published bridge matches the compiler version. */
    static void requireSupportedVersion(String version) {
        int major = 0;
        String[] parts = version.split("[.-]");
        if (parts.length > 0) major = parseLeadingInt(parts[0]);
        if (major < 3) {
            throw new IllegalArgumentException("jk requires Scala 3 or newer, but the project targets "
                    + version
                    + ". Pin a Scala 3 version in jk.toml (scala = \"3\").");
        }
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
    }

    private static Path cacheFile(Cas cas, String scalaVersion) {
        return cas.root()
                .resolve("tools")
                .resolve("scala")
                .resolve(scalaVersion)
                .resolve("closure.shas");
    }

    static List<Path> readCachedClosure(Path cacheFile, Cas cas) throws IOException {
        if (!Files.isRegularFile(cacheFile)) return null;
        List<Path> jars = new ArrayList<>();
        for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
            String sha = line.strip();
            if (sha.isEmpty()) continue;
            if (!cas.contains(sha)) return null;
            jars.add(cas.pathFor(sha));
        }
        return jars.isEmpty() ? null : jars;
    }

    static void writeCachedClosure(Path cacheFile, List<String> shas) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, shas, StandardCharsets.UTF_8);
    }
}
