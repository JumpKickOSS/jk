// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Default versions for scaffolds and tooling fallbacks — ONE source. The Giter8
 * bundles ({@code templates/quarkus.g8}, {@code clients/cli/src/main/resources/giter8}) and the
 * quarkus plugin's gradle compileOnly pin carry their own copies by design (data files /
 * separate toolchain); everything java-side reads this.
 */
public final class ToolDefaults {

    /** Quarkus platform major-line floor (caret) for scaffolds; lock pins exact (JK-1544). */
    public static final String QUARKUS_PLATFORM_VERSION = "3";

    /** Default Kotlin compiler version for scaffolds ({@code project.kotlin}). Keep in sync with KotlinResolver.DEFAULT_VERSION. */
    public static final String KOTLIN_DEFAULT_VERSION = "2.4.0";

    /** Default Groovy compiler version for scaffolds ({@code project.groovy}). Keep in sync with GroovyResolver.DEFAULT_VERSION. */
    public static final String GROOVY_DEFAULT_VERSION = "5.0.4";

    private ToolDefaults() {}
}
