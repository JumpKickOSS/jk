// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import java.net.URI;

/**
 * Picks the Kotlin distribution to use for compiling {@code .kt} sources. Mirrors {@link
 * cc.jumpkick.mvn.MavenResolver} / {@link cc.jumpkick.gradle.GradleResolver}: a default pinned
 * version, downloaded once into {@code $JK_STORE_DIR/tools/kotlin/<version>/} and reused
 * thereafter.
 *
 * <p>v0.6 first iteration: no project-level pin yet (a {@code kotlin} field on {@code
 * jk.toml} lands when more user code lives in Kotlin). {@link #defaultDistribution()} is the single
 * source of truth.
 */
public final class KotlinResolver {

    /**
     * jk's bundled default Kotlin version. Floor is 2.4.0: the Kotlin compile path runs the Build
     * Tools API via its {@code KotlinToolchains} entry point, which only exists in 2.4.0+.
     */
    public static final String DEFAULT_VERSION = "2.4.0";

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
