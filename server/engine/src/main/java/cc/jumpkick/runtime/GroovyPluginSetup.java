// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares everything a Groovy compile needs to fork the {@code jk-groovy-compiler} plugin: the
 * plugin classpath (the plugin jar + the resolved Groovy runtime closure, version-matched to the
 * project's Groovy) and the version-matched {@code groovy} jar for the compilation classpath.
 *
 * <p>Jar location itself is delegated to the shared {@link PluginJar} registry; this class adds
 * only the Groovy-specific closure resolution on top.
 */
public final class GroovyPluginSetup {

    /**
     * Override for the {@code jk-groovy-compiler} jar path (tests, dev). Takes precedence over the
     * CAS lookup.
     */
    public static final String WORKER_JAR_PROPERTY = PluginJar.GROOVY_COMPILER.jarProperty();

    private GroovyPluginSetup() {}

    /**
     * @param workerClasspath plugin {@code -cp}: plugin jar + Groovy closure
     * @param groovyJar the version-matched groovy jar for the compile classpath
     */
    public record Prepared(List<Path> workerClasspath, Path groovyJar) {}

    /**
     * Resolve the closure + groovy jar for {@code groovyVersion} (null ⇒ jk's default) against
     * {@code repos}, and locate the plugin jar.
     */
    public static Prepared prepare(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        String version =
                (groovyVersion == null || groovyVersion.isBlank()) ? GroovyResolver.DEFAULT_VERSION : groovyVersion;
        List<Path> closure = GroovyToolResolver.resolveClasspath(repos, cas, version);
        Path groovyJar = GroovyToolResolver.resolveGroovyJar(repos, cas, version);

        // Expand thin worker jar + optional .classpath sidecar (plugin-sdk, …) then Groovy closure.
        List<Path> workerClasspath =
                new ArrayList<>(cc.jumpkick.engine.plugin.WorkerLaunchClasspath.paths(locateWorkerJar(cas)));
        for (Path p : closure) {
            if (!workerClasspath.contains(p)) workerClasspath.add(p);
        }
        return new Prepared(workerClasspath, groovyJar);
    }

    /**
     * Locate the {@code jk-groovy-compiler} plugin jar via the shared registry: the {@value
     * #WORKER_JAR_PROPERTY} override, then the CAS by expected SHA.
     */
    public static Path locateWorkerJar(Cas cas) {
        return PluginJar.GROOVY_COMPILER.locate(cas);
    }
}
