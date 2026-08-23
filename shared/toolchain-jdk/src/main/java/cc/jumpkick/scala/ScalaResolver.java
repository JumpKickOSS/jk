// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scala;

import java.util.List;

/**
 * Picks the Scala 3 compiler used to compile {@code .scala} sources. Unlike a Kotlin
 * distribution zip, the compiler is Maven artifacts ({@code scala3-compiler_3} plus the
 * published {@code scala3-sbt-bridge}) resolved by the engine onto the java-compiler worker
 * classpath for mixed Java+Scala sessions. Java-only compiles never load these jars.
 */
public final class ScalaResolver {

    /**
     * jk's bundled default Scala 3 version. Floor is 3.0: Zinc's published Scala 3 bridge
     * matches the compiler version.
     */
    public static final String DEFAULT_VERSION = "3.8.4";

    public static final String COMPILER_MODULE = "org.scala-lang:scala3-compiler_3";

    public static final String BRIDGE_MODULE = "org.scala-lang:scala3-sbt-bridge";

    private ScalaResolver() {}

    /** Compiler + Zinc bridge GAs that belong on the mixed-compile worker classpath. */
    public static List<String> workerModules() {
        return List.of(COMPILER_MODULE, BRIDGE_MODULE);
    }
}
