// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.mvn.MavenResolver;

/**
 * Which distribution a {@link BuildTool} means, at a named version or at the version jk defaults
 * to.
 *
 * <p>Each tool owns its own URL shape — Maven's lives on {@code MavenResolver}, Gradle's on {@code
 * GradleResolver}, Kotlin's on {@code KotlinResolver} — because the three are unrelated release
 * layouts that happen to be fetched the same way. What this adds is the one place that turns "the
 * user said kotlin" into the right one of them, so a command and the engine's mid-build
 * provisioning cannot disagree about what {@code kotlin:latest} installs.
 *
 * <p>The switch is exhaustive over {@link BuildTool}: a fourth tool is an enum constant plus a
 * compile error here, not a silently unhandled name at the CLI.
 */
public final class BuildToolDistributions {

    private BuildToolDistributions() {}

    /**
     * The distribution for {@code tool} at {@code version}. {@code null}, blank and {@link
     * BuildTool#LATEST} all mean the tool's default — the same one the engine provisions when a
     * build needs the tool and nothing has pinned it.
     */
    public static ToolDistribution of(BuildTool tool, String version) {
        String v = version == null || version.isBlank() || BuildTool.LATEST.equalsIgnoreCase(version.trim())
                ? null
                : version;
        return switch (tool) {
            case MAVEN -> v == null ? MavenResolver.defaultDistribution() : MavenResolver.distributionFor(v);
            case GRADLE -> v == null ? GradleResolver.defaultDistribution() : GradleResolver.distributionFor(v);
            case KOTLIN -> v == null ? KotlinResolver.defaultDistribution() : KotlinResolver.distributionFor(v);
        };
    }
}
