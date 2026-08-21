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
import java.nio.file.StandardCopyOption;
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
            List<Path> named = listLibJars(libDir(cas, scalaVersion));
            if (named != null) return named;
        }

        List<Dependency> roots = List.of(
                new Dependency(ScalaResolver.COMPILER_MODULE, VersionSelector.parse("=" + scalaVersion)),
                new Dependency(ScalaResolver.BRIDGE_MODULE, VersionSelector.parse("=" + scalaVersion)));
        Resolution resolution = new PubGrubResolver(repos).resolve(roots);

        Path libDir = libDir(cas, scalaVersion);
        Files.createDirectories(libDir);
        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            Coordinate coord = mod.coordinate();
            var hit = repos.tryFetchArtifact(coord);
            if (hit.isEmpty()) continue;
            Path named = libDir.resolve(coord.artifact() + "-" + coord.version() + ".jar");
            if (!Files.isRegularFile(named) || Files.size(named) == 0) {
                Files.copy(hit.get().fetched().cachePath(), named, StandardCopyOption.REPLACE_EXISTING);
            }
            jars.add(named);
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

    /** Path of the version-matched {@code scala3-sbt-bridge} jar (named copy under {@code tools/scala/}). */
    public static Path resolveBridgeJar(RepoGroup repos, Cas cas, String scalaVersion)
            throws IOException, InterruptedException {
        return requiredJar(resolveClasspath(repos, cas, scalaVersion), "scala3-sbt-bridge");
    }

    /**
     * Project compile/runtime stdlib: {@code scala-library} (2.13 or 3.8+) when present, else
     * {@code scala3-library_3}. On Scala 3.8+ the latter is an empty stub.
     */
    public static Path resolveLibraryJar(RepoGroup repos, Cas cas, String scalaVersion)
            throws IOException, InterruptedException {
        List<Path> libs = libraryJars(resolveClasspath(repos, cas, scalaVersion));
        if (libs.isEmpty()) {
            throw new IOException("Scala compiler closure for " + scalaVersion + " is missing scala-library");
        }
        return libs.getFirst();
    }

    public static Path resolveCompilerJar(RepoGroup repos, Cas cas, String scalaVersion)
            throws IOException, InterruptedException {
        return requiredJar(resolveClasspath(repos, cas, scalaVersion), "scala3-compiler_3");
    }

    /**
     * Stdlib jars from a resolved compiler closure: {@code scala-library} first, then
     * {@code scala3-library_3} when present.
     */
    static List<Path> libraryJars(List<Path> compilerClasspath) {
        List<Path> out = new ArrayList<>();
        Path sl = findJar(compilerClasspath, "scala-library");
        Path s3 = findJar(compilerClasspath, "scala3-library_3");
        if (sl != null) out.add(sl);
        if (s3 != null) out.add(s3);
        return out;
    }

    static Path findJar(List<Path> jars, String artifactPrefix) {
        for (Path p : jars) {
            String n = p.getFileName().toString();
            if (n.startsWith(artifactPrefix + "-") || n.startsWith(artifactPrefix + ".")) return p;
        }
        return null;
    }

    static Path requiredJar(List<Path> jars, String artifactPrefix) throws IOException {
        Path p = findJar(jars, artifactPrefix);
        if (p == null) {
            throw new IOException("Scala compiler closure is missing " + artifactPrefix);
        }
        return p;
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

    private static Path libDir(Cas cas, String scalaVersion) {
        return cas.root()
                .resolve("tools")
                .resolve("scala")
                .resolve(scalaVersion)
                .resolve("lib");
    }

    private static List<Path> listLibJars(Path libDir) throws IOException {
        if (!Files.isDirectory(libDir)) return null;
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(libDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
        return jars.isEmpty() ? null : jars;
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
