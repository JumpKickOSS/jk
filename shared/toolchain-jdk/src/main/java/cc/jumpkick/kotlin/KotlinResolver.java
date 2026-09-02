// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import java.net.URI;

/**
 * Picks the Kotlin distribution to use for compiling {@code .kt} sources. Mirrors {@code
 * cc.jumpkick.mvn.MavenResolver} / {@code cc.jumpkick.gradle.GradleResolver}: a default pinned
 * version, downloaded once into {@code $JK_STORE_DIR/tools/kotlin/<version>/} and reused
 * thereafter.
 *
 * <p>v0.6 first iteration: no project-level pin yet (a {@code kotlin} field on {@code
 * jk.toml} lands when more user code lives in Kotlin). {@link #defaultDistribution()} is the single
 * source of truth.
 */
public final class KotlinResolver {

    /**
     * jk's lowest supported Kotlin. Two reasons stack:
     *
     * <ul>
     *   <li>The Kotlin compile path runs the Build Tools API via its {@code KotlinToolchains} entry
     *       point, which only exists in 2.4.0+.
     *   <li>2.4.0 itself is unusable for jk: its K2 frontend cannot compile a script using {@code
     *       @file:Import} ({@code Expected FirResolvedTypeRef with ConeKotlinType but was
     *       FirUserTypeRefImpl}), which build logic needs in order to split a large script without
     *       compiling several. Reproduced against Kotlin's own {@code main.kts} definition, so it is
     *       the compiler and not jk's wiring. 2.4.10 fixes it.
     * </ul>
     *
     * <p>The floor is therefore a patch version. {@link #FLOOR_PATCH} exists so the version guard
     * compares against this constant rather than spelling {@code 10} again somewhere else.
     */
    public static final String FLOOR_VERSION = "2.4.10";

    /** Patch component of {@link #FLOOR_VERSION}, for the {@code 2.4.x} arm of a version guard. */
    public static final int FLOOR_PATCH = 10;

    /** jk's bundled default Kotlin version. Never below {@link #FLOOR_VERSION}. */
    public static final String DEFAULT_VERSION = FLOOR_VERSION;

    private static final String DEFAULT_BASE = "https://github.com/JetBrains/kotlin/releases/download/";

    private KotlinResolver() {}

    public static ToolDistribution defaultDistribution() {
        return distributionFor(DEFAULT_VERSION);
    }

    /**
     * The distribution for an explicit Kotlin version.
     *
     * <p>One place builds this URL. It was two — {@code CompileToolchain.resolveKotlinHome} assembled
     * the same release path inline for its version-override branch — and a download URL spelled
     * twice is a rename that half-lands: one caller keeps fetching from a path the other has moved
     * off, and the only symptom is a 404 in whichever branch was not updated.
     */
    public static ToolDistribution distributionFor(String version) {
        String v = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
        URI uri = URI.create(DEFAULT_BASE + "v" + v + "/kotlin-compiler-" + v + ".zip");
        return new ToolDistribution(BuildTool.KOTLIN, v, uri, "zip");
    }
}
