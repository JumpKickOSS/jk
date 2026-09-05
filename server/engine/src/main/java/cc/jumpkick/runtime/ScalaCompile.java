// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.scala.ScalaResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the Scala 3 compiler closure for a mixed Java+Scala {@code compile-java} (or test)
 * session: worker extra CP plus the version-matched stdlib jars for the project compile
 * classpath ({@code scala-library}, and {@code scala3-library_3} when present).
 */
public final class ScalaCompile {

    public record Setup(
            String version, List<Path> compilerClasspath, List<Path> libraryJars, Path compilerJar, Path bridgeJar) {
        /** Preferred stdlib jar ({@code scala-library} on 3.8+, else {@code scala3-library_3}). */
        public @Nullable Path libraryJar() {
            return libraryJars == null || libraryJars.isEmpty() ? null : libraryJars.getFirst();
        }
    }

    private ScalaCompile() {}

    public static Setup prepare(JkBuild project, Lockfile lock, Cas cas) throws IOException {
        String version = CompileToolchain.scalaVersionFor(lock, project);
        if (version == null || version.isBlank()) {
            version = ScalaResolver.DEFAULT_VERSION;
            // A declared non-exact selector (e.g. `scala = "3"`) with no resolved pin in the lock —
            // offline first-resolve, or a metadata fetch that failed — was silently compiling with
            // the bundled default, possibly outside the requested range.
            var selector = project == null ? null : project.project().scala();
            if (selector != null) {
                System.err.println("jk: warning: no resolved Scala version in the lock for selector `"
                        + selector.raw() + "` — falling back to " + version
                        + "; run `jk lock` online to pin the intended version.");
            }
        }
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            List<Path> compilerCp = ScalaToolResolver.resolveClasspath(repos, cas, version);
            List<Path> libraryJars = ScalaToolResolver.libraryJars(compilerCp);
            if (libraryJars.isEmpty()) {
                throw new IOException("Scala compiler closure for " + version + " is missing scala-library");
            }
            return new Setup(
                    version,
                    compilerCp,
                    libraryJars,
                    ScalaToolResolver.requiredJar(compilerCp, "scala3-compiler_3"),
                    ScalaToolResolver.requiredJar(compilerCp, "scala3-sbt-bridge"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Scala compiler", e);
        }
    }
}
