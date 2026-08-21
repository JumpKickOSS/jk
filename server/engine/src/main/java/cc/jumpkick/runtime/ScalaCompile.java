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

/**
 * Resolves the Scala 3 compiler closure for a mixed Java+Scala {@code compile-java} (or test)
 * session: worker extra CP plus the version-matched {@code scala3-library_3} jar for the project
 * compile classpath.
 */
public final class ScalaCompile {

    public record Setup(String version, List<Path> compilerClasspath, Path libraryJar) {}

    private ScalaCompile() {}

    public static Setup prepare(JkBuild project, Lockfile lock, Cas cas) throws IOException {
        String version = CompileToolchain.scalaVersionFor(lock, project);
        if (version == null || version.isBlank()) version = ScalaResolver.DEFAULT_VERSION;
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            List<Path> compilerCp = ScalaToolResolver.resolveClasspath(repos, cas, version);
            Path library = null;
            for (Path p : compilerCp) {
                if (p.getFileName().toString().startsWith("scala3-library_3-")) {
                    library = p;
                    break;
                }
            }
            if (library == null) {
                throw new IOException("scala3-library_3:" + version + " missing from the compiler closure");
            }
            return new Setup(version, compilerCp, library);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Scala compiler", e);
        }
    }
}
