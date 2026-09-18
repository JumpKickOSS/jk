// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy;

import cc.jumpkick.version.Versions;

/**
 * Picks the Groovy version used to compile {@code .groovy} sources. Unlike {@link
 * cc.jumpkick.kotlin.KotlinResolver} there is no tool distribution to install: the compiler is a
 * plain Maven artifact ({@code org.apache.groovy:groovy}) resolved by the engine onto the
 * groovy-compiler worker's classpath.
 */
public final class GroovyResolver {

    /**
     * The oldest Groovy line jk's compiler worker drives: it runs the Groovy 5 compiler APIs. A
     * project declaring a Groovy below it compiles with {@link #DEFAULT_VERSION} ({@link
     * #floored}): the lock pins that runtime and notes it, and the build warns once per module.
     */
    public static final String FLOOR_VERSION = "5.0";

    /** jk's bundled default Groovy version. Never below {@link #FLOOR_VERSION}. */
    public static final String DEFAULT_VERSION = "5.0.4";

    private GroovyResolver() {}

    /** True when {@code version} sorts below {@link #FLOOR_VERSION} as a Maven version, a pre-release of the floor included. */
    public static boolean belowFloor(String version) {
        return Versions.compare(version.trim(), FLOOR_VERSION) < 0;
    }

    /** The compiler jk drives for a declared {@code version}: itself, or {@link #DEFAULT_VERSION} when it is below the floor. */
    public static String floored(String version) {
        return belowFloor(version) ? DEFAULT_VERSION : version;
    }

    /**
     * The one sentence for a manifest whose {@code groovy} is below the floor: what jk compiles with
     * and what to check. The lock and the build both say it.
     */
    public static String floorNote(String declared) {
        return "groovy " + declared + " is below jk's floor " + FLOOR_VERSION
                + ", the oldest Groovy its compiler worker drives, so the module compiles and runs with "
                + DEFAULT_VERSION
                + " — check the build against that release's warnings and language changes, and write groovy = \""
                + DEFAULT_VERSION + "\" to have the manifest say so";
    }
}
