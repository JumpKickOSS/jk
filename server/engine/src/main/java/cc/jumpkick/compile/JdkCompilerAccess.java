// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * JVM flags that open {@code jdk.compiler}'s internals to unnamed modules. Javac plugins (Error
 * Prone, NullAway) and the javac-based formatters reach past {@code com.sun.source} into
 * {@code com.sun.tools.javac.*}; without these the plugin dies at load with an
 * {@code IllegalAccessError}. Not an action-key input: they change what the worker JVM permits,
 * not what javac emits.
 */
@NullMarked
public final class JdkCompilerAccess {

    private JdkCompilerAccess() {}

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
}
