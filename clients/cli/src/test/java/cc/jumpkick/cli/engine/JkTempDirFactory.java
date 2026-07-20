// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * Prefer short {@code /tmp} paths for UDS-friendly state (ticket-1021). Cleanup is owned by {@link
 * JkTempDirDeletionStrategy} (ticket-1055).
 */
public final class JkTempDirFactory implements TempDirFactory {

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws Exception {
        Path root = Files.isDirectory(Path.of("/tmp"))
                ? Path.of("/tmp")
                : Path.of(System.getProperty("java.io.tmpdir"));
        return Files.createTempDirectory(root, "jk-junit-");
    }
}
