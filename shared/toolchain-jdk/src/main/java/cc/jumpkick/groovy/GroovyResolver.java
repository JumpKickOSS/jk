// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy;

/**
 * Picks the Groovy version used to compile {@code .groovy} sources. Unlike {@link
 * cc.jumpkick.kotlin.KotlinResolver} there is no tool distribution to install: the compiler is a
 * plain Maven artifact ({@code org.apache.groovy:groovy}) resolved by the engine onto the
 * groovy-compiler worker's classpath.
 */
public final class GroovyResolver {

    /**
     * jk's bundled default Groovy version. Floor is 5.0: the groovy-compiler worker drives the
     * Groovy 5 compiler APIs.
     */
    public static final String DEFAULT_VERSION = "5.0.4";

    private GroovyResolver() {}
}
