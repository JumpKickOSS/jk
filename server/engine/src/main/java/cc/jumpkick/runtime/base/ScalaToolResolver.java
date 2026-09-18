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
import cc.jumpkick.scala.ScalaResolver;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolves/fetches the Scala 3 compiler + published sbt bridge (and transitives) into the CAS
 * under {@code tools/scala/}. That closure is extra worker classpath for mixed Java+Scala
 * Zinc sessions — not the project's compile classpath. A version below the {@value
 * ScalaResolver#FLOOR_VERSION} floor resolves {@link ScalaResolver#DEFAULT_VERSION}'s closure.
 */
public final class ScalaToolResolver {

    private ScalaToolResolver() {}

    /**
     * Resolve and fetch compiler + bridge for {@code scalaVersion}, returning local jar paths
     * for the java-compiler worker {@code -cp}.
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String declaredVersion)
            throws IOException, InterruptedException {
        String scalaVersion = ScalaResolver.floored(declaredVersion);
        Path cacheFile = cacheFile(cas, scalaVersion);
        Path libDir = libDir(cas, scalaVersion);
        boolean refresh = SessionContext.current().config().forceOr(false);
        if (!refresh) {
            List<Path> cached = readValidatedClosure(libDir, cacheFile);
            if (cached != null) return cached;
        }

        List<Dependency> roots = List.of(
                new Dependency(ScalaResolver.COMPILER_MODULE, VersionSelector.parse("=" + scalaVersion)),
                new Dependency(ScalaResolver.BRIDGE_MODULE, VersionSelector.parse("=" + scalaVersion)));
        Resolution resolution = new PubGrubResolver(repos).resolve(roots);

        Files.createDirectories(libDir);
        // Drop the completion marker up front: a crash mid-copy then leaves no gate, so the next
        // build re-resolves cleanly instead of trusting a half-written lib dir.
        Files.deleteIfExists(cacheFile);
        int expected = resolution.modules().size();
        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            Coordinate coord = mod.coordinate();
            var hit = repos.tryFetchArtifact(coord);
            if (hit.isEmpty()) continue;
            Path named = libDir.resolve(coord.artifact() + "-" + coord.version() + ".jar");
            // Copy through a unique temp + atomic move so a torn/partial copy is never observed by a
            // concurrent build, and --force re-copies unconditionally.
            Path tmp = Files.createTempFile(libDir, "." + named.getFileName() + ".", ".part");
            // Failure path only — moveInto consumed tmp on success.
            boolean moved = false;
            try {
                Files.copy(hit.get().fetched().cachePath(), tmp, StandardCopyOption.REPLACE_EXISTING);
                AtomicWrites.moveInto(tmp, named);
                moved = true;
            } finally {
                if (!moved) Files.deleteIfExists(tmp);
            }
            jars.add(named);
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.size() != expected) {
            // A module in the resolved closure failed to fetch — refuse to bless a partial closure
            // (scalac would later die with NoClassDefFoundError). No marker is written, so the next
            // build retries from scratch.
            throw new IOException("Scala compiler closure for "
                    + scalaVersion
                    + " is incomplete: fetched " + jars.size() + " of " + expected
                    + " modules — check that " + ScalaResolver.COMPILER_MODULE + ":" + scalaVersion
                    + " and its transitives are available in the configured repositories.");
        }
        pruneOrphanJars(libDir, jars);
        // Write the completion marker LAST — its presence + jar-count match is the read-path gate.
        writeCachedClosure(cacheFile, shas);
        return jars;
    }

    /**
     * The compiler closure a build already fetched for {@code scalaVersion}, or empty: the IDE
     * model reads what the store holds and never downloads a compiler.
     */
    public static List<Path> cachedClasspath(Cas cas, String scalaVersion) {
        try {
            List<Path> cached = readValidatedClosure(libDir(cas, scalaVersion), cacheFile(cas, scalaVersion));
            return cached == null ? List.of() : cached;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Return the cached named-jar closure only when it is provably complete: the {@code closure.shas}
     * completion marker exists (written last, so a crashed resolve has none) and the lib dir holds
     * exactly as many jars as it records. A partial lib dir returns {@code null} → re-resolve.
     */
    static @Nullable List<Path> readValidatedClosure(Path libDir, Path cacheFile) throws IOException {
        if (!Files.isRegularFile(cacheFile)) return null;
        int recorded = recordedShaCount(cacheFile);
        List<Path> jars = listLibJars(libDir);
        if (jars == null || jars.size() != recorded) return null;
        return jars;
    }

    private static int recordedShaCount(Path cacheFile) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
            if (!line.strip().isEmpty()) n++;
        }
        return n;
    }

    /** Delete any {@code .jar} in {@code libDir} that is not part of the freshly resolved closure. */
    private static void pruneOrphanJars(Path libDir, List<Path> keep) throws IOException {
        Set<Path> wanted = new HashSet<>(keep);
        try (var stream = Files.list(libDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName().toString().endsWith(".jar") && !wanted.contains(p)) {
                    Files.deleteIfExists(p);
                }
            }
        }
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
    public static List<Path> libraryJars(List<Path> compilerClasspath) {
        List<Path> out = new ArrayList<>();
        Path sl = findJar(compilerClasspath, "scala-library");
        Path s3 = findJar(compilerClasspath, "scala3-library_3");
        if (sl != null) out.add(sl);
        if (s3 != null) out.add(s3);
        return out;
    }

    static @Nullable Path findJar(List<Path> jars, String artifactPrefix) {
        for (Path p : jars) {
            String n = p.getFileName().toString();
            if (n.startsWith(artifactPrefix + "-") || n.startsWith(artifactPrefix + ".")) return p;
        }
        return null;
    }

    public static Path requiredJar(List<Path> jars, String artifactPrefix) throws IOException {
        Path p = findJar(jars, artifactPrefix);
        if (p == null) {
            throw new IOException("Scala compiler closure is missing " + artifactPrefix);
        }
        return p;
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

    private static @Nullable List<Path> listLibJars(Path libDir) throws IOException {
        if (!Files.isDirectory(libDir)) return null;
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(libDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
        return jars.isEmpty() ? null : jars;
    }

    static void writeCachedClosure(Path cacheFile, List<String> shas) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, shas, StandardCharsets.UTF_8);
    }
}
