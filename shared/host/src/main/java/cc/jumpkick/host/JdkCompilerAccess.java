// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.List;

/**
 * JVM flags that open {@code jdk.compiler}'s internals to unnamed modules. Javac plugins (Error
 * Prone, NullAway) and the javac-based formatters reach past {@code com.sun.source} into
 * {@code com.sun.tools.javac.*}; without these the plugin dies at load with an
 * {@code IllegalAccessError}. Every compiler worker starts with them, so a build that spells one
 * as a {@code -J} argument adds nothing, and {@code jk import} drops such a line. Not an
 * action-key input: they change what the worker JVM permits, not what javac emits.
 */
public final class JdkCompilerAccess {

    private JdkCompilerAccess() {}

    /** javac's launcher spelling of a flag for the JVM running the compiler: {@code -J<flag>}. */
    public static final String LAUNCHER_PREFIX = "-J";

    /** The set Error Prone documents for JDK 16+; a superset of what google-java-format needs. */
    public static final List<String> JVM_FLAGS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

    /** True when {@code arg} is a {@code -J} spelling of a flag in {@link #JVM_FLAGS}. */
    public static boolean grants(String arg) {
        return arg.startsWith(LAUNCHER_PREFIX) && JVM_FLAGS.contains(arg.substring(LAUNCHER_PREFIX.length()));
    }
}
