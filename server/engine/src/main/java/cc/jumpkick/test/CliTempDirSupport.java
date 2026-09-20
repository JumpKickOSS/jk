// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.jar.JarFile;

/**
 * Whether a module's test classpath can load jk-cli's {@code @TempDir} factory and deletion
 * strategy.
 *
 * <p>{@link JUnitLauncher} names them to the forked JVM with {@code
 * -Djunit.jupiter.tempdir.factory.default}. Naming them for a module that cannot load them is not
 * a no-op: JUnit logs a stack trace per {@code @TempDir} and falls back to the default deletion
 * strategy, which is the soft-fail delete the flag exists to install.
 *
 * <p>The gate is the composed classpath, not a {@code [test] env} declaration or the module's
 * name: only a classpath that actually carries the factory can load it, and the module that owns
 * those classes is free to move.
 */
final class CliTempDirSupport {

    private CliTempDirSupport() {}

    /** The factory jk-cli registers for its own suites; its presence stands for the pair. */
    /** The short-path {@code @TempDir} factory every module's fixtures carry. */
    static final String FACTORY_RESOURCE = "cc/jumpkick/testing/ShortTempDirFactory.class";

    /** jk-cli's deletion strategy, which stops the resident engine before a retry; jk-cli's tests only. */
    static final String STRATEGY_RESOURCE = "cc/jumpkick/cli/engine/JkTempDirDeletionStrategy.class";

    /** True when {@code classpath} carries the classes — a classes dir holding it, or a jar with the entry. */
    static boolean onClasspath(Collection<Path> classpath, String resource) {
        for (Path entry : classpath) {
            if (entry == null) continue;
            if (Files.isDirectory(entry)) {
                if (Files.isRegularFile(entry.resolve(resource))) return true;
                continue;
            }
            if (!Files.isRegularFile(entry)) continue;
            try (JarFile jar = new JarFile(entry.toFile())) {
                if (jar.getEntry(resource) != null) return true;
            } catch (IOException notAJar) {
                // A classpath entry that is not a readable jar simply does not carry them.
            }
        }
        return false;
    }
}
