// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scala;

import cc.jumpkick.version.Versions;
import java.util.List;

/**
 * Picks the Scala 3 compiler used to compile {@code .scala} sources. Unlike a Kotlin
 * distribution zip, the compiler is Maven artifacts ({@code scala3-compiler_3} plus the
 * published {@code scala3-sbt-bridge}) resolved by the engine onto the java-compiler worker
 * classpath for mixed Java+Scala sessions. Java-only compiles never load these jars.
 */
public final class ScalaResolver {

    /**
     * The oldest Scala line jk drives: Scala 3, whose published sbt bridge matches the compiler
     * version Zinc loads. A project declaring a Scala below it compiles with {@link
     * #DEFAULT_VERSION} ({@link #floored}): the lock pins that compiler and notes it, and the build
     * warns once per module.
     */
    public static final String FLOOR_VERSION = "3.0";

    /** jk's bundled default Scala 3 version. Never below {@link #FLOOR_VERSION}. */
    public static final String DEFAULT_VERSION = "3.8.4";

    public static final String COMPILER_MODULE = "org.scala-lang:scala3-compiler_3";

    public static final String BRIDGE_MODULE = "org.scala-lang:scala3-sbt-bridge";

    private ScalaResolver() {}

    /** Compiler + Zinc bridge GAs that belong on the mixed-compile worker classpath. */
    public static List<String> workerModules() {
        return List.of(COMPILER_MODULE, BRIDGE_MODULE);
    }

    /** True when {@code version} sorts below {@link #FLOOR_VERSION} as a Maven version, a pre-release of the floor included. */
    public static boolean belowFloor(String version) {
        return Versions.compare(version.trim(), FLOOR_VERSION) < 0;
    }

    /** The compiler jk drives for a declared {@code version}: itself, or {@link #DEFAULT_VERSION} when it is below the floor. */
    public static String floored(String version) {
        return belowFloor(version) ? DEFAULT_VERSION : version;
    }

    /**
     * The one sentence for a manifest whose {@code scala} is below the floor: what jk compiles with
     * and what to check. The lock and the build both say it.
     */
    public static String floorNote(String declared) {
        return "scala " + declared + " is below jk's floor " + FLOOR_VERSION
                + ", the oldest Scala its compile path drives, so the module compiles with " + DEFAULT_VERSION
                + " and its library — check the build against Scala 3's source changes, and write scala = \""
                + DEFAULT_VERSION + "\" to have the manifest say so";
    }
}
