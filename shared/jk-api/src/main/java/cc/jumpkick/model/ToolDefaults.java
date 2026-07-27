// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Default versions for scaffolds and tooling fallbacks — ONE source (JK-1235). The Giter8
 * bundles ({@code templates/quarkus.g8}, {@code clients/cli/src/main/resources/giter8}) and the
 * quarkus plugin's gradle compileOnly pin carry their own copies by design (data files /
 * separate toolchain); everything java-side reads this.
 */
public final class ToolDefaults {

    /** {@code io.quarkus.platform:quarkus-bom} default for scaffolds and the tooling pom. */
    public static final String QUARKUS_PLATFORM_VERSION = "3.28.5";

    private ToolDefaults() {}
}
