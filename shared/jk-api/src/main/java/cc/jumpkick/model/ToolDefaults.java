// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * The one tooling version jk has to hardcode: the Quarkus platform BOM for the generated test
 * tooling POM. Language compiler defaults are NOT here — {@code KotlinResolver.DEFAULT_VERSION},
 * {@code GroovyResolver.DEFAULT_VERSION} and {@code ScalaResolver.DEFAULT_VERSION} own theirs, and
 * a second copy here was a "keep in sync" comment standing in for a compiler. Giter8 bundles
 * ({@code templates/quarkus.g8}, {@code clients/cli/src/main/resources/giter8}) and the quarkus
 * plugin's gradle compileOnly pin carry their own copies by design — they are data files and a
 * separate toolchain, neither of which can read a Java constant.
 */
public final class ToolDefaults {

    /**
     * Concrete {@code io.quarkus.platform:quarkus-bom} version for the generated Maven tooling POM
     * that {@code @QuarkusTest}'s bootstrap reads. Only a last resort — the POM prefers the
     * version {@code jk-lock.toml} actually pinned.
     *
     * <p>Never write a bare major line into a Maven POM: Maven has no caret, so {@code quarkus-bom:3}
     * is a literal version that does not exist.
     */
    public static final String QUARKUS_TOOLING_BOM_VERSION = "3.38.0";

    private ToolDefaults() {}
}
