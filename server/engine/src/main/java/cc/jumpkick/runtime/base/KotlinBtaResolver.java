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
 * coordinate, the {@value KotlinResolver#FLOOR_VERSION} floor, and the version-matched stdlib. The
 * resolve, fetch and CAS closure cache themselves are {@link ToolClosure}, shared with {@link
 * GroovyToolResolver}.
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
     * @param kotlinVersion the exact Kotlin version to match (e.g. {@code 2.4.10})
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
     * Guard the {@value KotlinResolver#FLOOR_VERSION} floor. Two reasons stack, which is why the
     * floor is a patch version and not just {@code 2.4}:
     *
     * <ul>
     *   <li>The plugin drives the Build Tools API through its {@code KotlinToolchains} entry point,
     *       which does not exist before 2.4.0.
     *   <li>2.4.0 itself is a bad release for jk: its K2 frontend cannot compile a script using
     *       {@code @file:Import}, failing with {@code Expected FirResolvedTypeRef with
     *       ConeKotlinType but was FirUserTypeRefImpl}. Build logic depends on that annotation to
     *       split a large script without paying to compile several. 2.4.10 fixes it.
     * </ul>
     *
     * <p>Pre-releases of the floor itself ({@code 2.4.0-RC2}) sort below it and are rejected: the
     * bug is in that line, not after it.
     */
    static void requireSupportedVersion(String version) {
        int major = ToolClosure.versionPart(version, 0);
        int minor = ToolClosure.versionPart(version, 1);
        int patch = ToolClosure.versionPart(version, 2);
        boolean tooOld = major < 2
                || (major == 2 && minor < 4)
                || (major == 2 && minor == 4 && patch < KotlinResolver.FLOOR_PATCH);
        if (tooOld) {
            throw new IllegalArgumentException("jk requires Kotlin "
                    + KotlinResolver.FLOOR_VERSION
                    + " or newer, but the project targets "
                    + version
                    + ". 2.4.0 cannot compile build logic that uses @file:Import. "
                    + "Pin a newer version in jk.toml (project.kotlin).");
        }
    }
}
