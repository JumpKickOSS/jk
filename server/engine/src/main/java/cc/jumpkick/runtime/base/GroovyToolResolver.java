// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.groovy.GroovyResolver;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What is Groovy-specific about resolving the Groovy runtime closure: the root coordinate, the
 * {@value GroovyResolver#FLOOR_VERSION} floor a version below it is raised past, and the single
 * {@code groovy} jar. The resolve, fetch and CAS closure cache themselves are {@link ToolClosure},
 * shared with {@link KotlinBtaResolver}.
 */
public final class GroovyToolResolver {

    /** The single root coordinate; its POM drags in the Groovy runtime closure. */
    public static final String GROOVY_MODULE = "org.apache.groovy:groovy";

    private GroovyToolResolver() {}

    /**
     * Resolve and fetch the Groovy closure ({@code groovy} + transitives) for {@code groovyVersion}
     * (Maven via {@link PubGrubResolver}, CAS-cached under {@code tools/groovy/}), returning the
     * local jar paths for the plugin's classpath. The same closure serves both the groovy-compiler
     * worker's classpath and runtime injection onto the app classpath.
     *
     * @param repos the repositories to resolve against (build via {@link RepoGroupBuilder#buildFor},
     *     so project mirrors / credentials apply)
     * @param cas the content-addressed store the jars land in
     * @param groovyVersion the exact Groovy version to match (e.g. {@code 5.0.4}); one below the
     *     floor resolves {@link GroovyResolver#DEFAULT_VERSION}'s closure
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        String version = GroovyResolver.floored(groovyVersion);
        return ToolClosure.resolve(repos, cas, "groovy", "Groovy", GROOVY_MODULE, version);
    }

    /**
     * The runtime-injection closure for {@code groovyVersion}: the seam that puts Groovy on the
     * app's runtime classpath. Same resolution (and cache) as {@link #resolveClasspath}.
     */
    public static List<Path> resolveRuntime(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        return resolveClasspath(repos, cas, groovyVersion);
    }

    /**
     * Resolve (and fetch into the CAS) the version-matched single {@code groovy} jar. It must go on
     * the <em>compilation</em> classpath: user Groovy code compiles against the Groovy runtime
     * types. A version below the floor is the floored compiler's own jar.
     */
    public static Path resolveGroovyJar(RepoGroup repos, Cas cas, String groovyVersion)
            throws IOException, InterruptedException {
        String version = GroovyResolver.floored(groovyVersion);
        return ToolClosure.single(repos, Coordinate.of("org.apache.groovy", "groovy", version));
    }
}
