// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What is Kotlin-specific about resolving the Kotlin Build Tools API impl closure: the root
 * coordinate, the {@value KotlinResolver#FLOOR_VERSION} floor a version below it is raised to, and
 * the version-matched stdlib. The resolve, fetch and CAS closure cache themselves are {@link
 * ToolClosure}, shared with {@link GroovyToolResolver}.
 */
public final class KotlinBtaResolver {

    /** The single root coordinate; its POM drags in the whole compiler closure. */
    public static final String BTA_IMPL_MODULE = "org.jetbrains.kotlin:kotlin-build-tools-impl";

    private KotlinBtaResolver() {}

    /**
     * Resolve and fetch the Build Tools API impl closure for {@code kotlinVersion} (Maven via
     * {@link PubGrubResolver}, CAS-cached under {@code tools/kotlin-bta/}), returning the local jar
     * paths for the plugin's classpath.
     *
     * @param repos the repositories to resolve against (build via {@link RepoGroupBuilder#buildFor},
     *     so project mirrors / credentials apply)
     * @param cas the content-addressed store the jars land in
     * @param kotlinVersion the exact Kotlin version to match (e.g. {@code 2.4.10}); one below the
     *     floor resolves the floor's closure
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        String version = KotlinResolver.floored(kotlinVersion);
        return ToolClosure.resolve(repos, cas, "kotlin-bta", "Kotlin Build Tools", BTA_IMPL_MODULE, version);
    }

    /**
     * Resolve (and fetch into the CAS) the version-matched {@code kotlin-stdlib} jar. It must go on
     * the <em>compilation</em> classpath: the plugin runs the compiler in-process with no Kotlin
     * distribution, so — unlike the old {@code kotlinc} — nothing auto-supplies the stdlib. The
     * caller pairs this with {@code -no-stdlib}. A version below the floor pairs the floor's stdlib,
     * the compiler's own.
     */
    public static Path resolveStdlib(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        String version = KotlinResolver.floored(kotlinVersion);
        return ToolClosure.single(repos, Coordinate.of("org.jetbrains.kotlin", "kotlin-stdlib", version));
    }
}
