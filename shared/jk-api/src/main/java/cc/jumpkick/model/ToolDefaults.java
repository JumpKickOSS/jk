// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Default versions for scaffolds and tooling fallbacks — ONE source. The Giter8
 * bundles ({@code templates/quarkus.g8}, {@code clients/cli/src/main/resources/giter8}) and the
 * quarkus plugin's gradle compileOnly pin carry their own copies by design (data files /
 * separate toolchain); everything java-side reads this.
 */
public final class ToolDefaults {

    /**
     * Quarkus platform major-line floor for scaffolded {@code jk.toml} ({@code [quarkus] version}),
     * where jk's bare-is-caret rule makes it a floor and the lock pins exact.
     *
     * <p>Never write this into a Maven POM: Maven has no caret, so {@code quarkus-bom:3} is a
     * literal version that does not exist. Use {@link #QUARKUS_TOOLING_BOM_VERSION} there.
     */
    public static final String QUARKUS_PLATFORM_FLOOR = "3";

    /**
     * Concrete {@code io.quarkus.platform:quarkus-bom} version for the generated Maven tooling POM
     * that {@code @QuarkusTest}'s bootstrap reads. Only a last resort — the POM prefers the
     * version {@code jk-lock.toml} actually pinned.
     */
    public static final String QUARKUS_TOOLING_BOM_VERSION = "3.38.0";

    /** Default Kotlin compiler version for scaffolds ({@code kotlin}). Keep in sync with KotlinResolver.DEFAULT_VERSION. */
    public static final String KOTLIN_DEFAULT_VERSION = "2.4.0";

    /** Default Groovy compiler version for scaffolds ({@code groovy}). Keep in sync with GroovyResolver.DEFAULT_VERSION. */
    public static final String GROOVY_DEFAULT_VERSION = "5.0.4";

    private ToolDefaults() {}
}
