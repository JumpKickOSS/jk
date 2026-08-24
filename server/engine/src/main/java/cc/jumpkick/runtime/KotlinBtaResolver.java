// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PubGrubResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What is Kotlin-specific about resolving the Kotlin Build Tools API impl closure: the root
 * coordinate, the 2.4.0 floor, and the version-matched stdlib. The resolve, fetch and CAS closure
 * cache themselves are {@link ToolClosure}, shared with {@link GroovyToolResolver}.
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
     * @param kotlinVersion the exact Kotlin version to match (e.g. {@code 2.4.0})
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        requireSupportedVersion(kotlinVersion);
        return ToolClosure.resolve(repos, cas, "kotlin-bta", "Kotlin Build Tools", BTA_IMPL_MODULE, kotlinVersion);
    }

    /**
     * Resolve (and fetch into the CAS) the version-matched {@code kotlin-stdlib} jar. It must go on
     * the <em>compilation</em> classpath: the plugin runs the compiler in-process with no Kotlin
     * distribution, so — unlike the old {@code kotlinc} — nothing auto-supplies the stdlib. The
     * caller pairs this with {@code -no-stdlib}.
     */
    public static Path resolveStdlib(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        return ToolClosure.single(repos, Coordinate.of("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion));
    }

    /**
     * Guard the 2.4.0 floor: the plugin drives the Build Tools API through its {@code
     * KotlinToolchains} entry point, which does not exist before 2.4.0.
     */
    static void requireSupportedVersion(String version) {
        int major = ToolClosure.versionPart(version, 0);
        int minor = ToolClosure.versionPart(version, 1);
        if (major < 2 || (major == 2 && minor < 4)) {
            throw new IllegalArgumentException("jk requires Kotlin 2.4.0 or newer (Build Tools API), but the project "
                    + "targets "
                    + version
                    + ". Pin a newer version in jk.toml (project.kotlin).");
        }
    }
}
