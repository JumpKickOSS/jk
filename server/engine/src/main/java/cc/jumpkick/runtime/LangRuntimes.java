// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyToolResolver;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The language runtimes a Kotlin or Groovy module's output needs beside it when it runs: the test
 * JVM gets them on its classpath beside the module's own.
 */
final class LangRuntimes {

    private LangRuntimes() {}

    /**
     * The version-matched Groovy runtime closure (already in the CAS from the worker setup).
     * Groovy output needs it on the <em>runtime</em> classpath — compilation pairs the groovy jar
     * onto the compile classpath, but the JVM still needs the full runtime closure when the code
     * runs (mirrors {@link #kotlinStdlib}).
     */
    static List<Path> groovyRuntime(JkBuild project, Lockfile lock, Cas cas) throws IOException {
        String groovyVersion = CompileToolchain.groovyVersionFor(lock, project);
        if (groovyVersion == null || groovyVersion.isBlank()) {
            groovyVersion = GroovyResolver.DEFAULT_VERSION;
        }
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            return GroovyToolResolver.resolveRuntime(repos, cas, groovyVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Groovy runtime", e);
        }
    }

    /**
     * The version-matched {@code kotlin-stdlib} path (already in the CAS from the plugin closure).
     * Kotlin output needs it on the <em>runtime</em> classpath too — compilation pairs the stdlib
     * with {@code -no-stdlib}, but the JVM still needs {@code kotlin.jvm.internal.*} etc. when the
     * code runs.
     */
    static Path kotlinStdlib(JkBuild project, Lockfile lock, Cas cas) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(lock, project);
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            return KotlinPluginSetup.prepare(repos, cas, kotlinVersion).stdlib();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin stdlib", e);
        }
    }
}
