// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * JUnit 6 TempDir cleanup for wire CLI tests (ticket-1055).
 *
 * <ol>
 *   <li>Try the standard recursive delete.
 *   <li>On failure, force-stop the resident engine (releases CAS hardlinks / jar FDs under the
 *       project tree), GC, retry.
 *   <li>If still stuck, return a successful empty result so the suite continues; AfterAll still
 *       stops the engine.
 * </ol>
 *
 * <p>Enables an empty {@code STOP_ENGINE_AFTER_EACH} denylist while keeping warm-engine speed for
 * tests whose TempDir trees are not pinned.
 */
public final class JkTempDirDeletionStrategy implements TempDirDeletionStrategy {

    @Override
    public DeletionResult delete(
            Path rootDir, AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        DeletionResult first = Standard.INSTANCE.delete(rootDir, elementContext, extensionContext);
        if (first.isSuccessful()) return first;

        EngineTestSupport.stopEngineOnly();
        System.gc();
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        DeletionResult second = Standard.INSTANCE.delete(rootDir, elementContext, extensionContext);
        if (second.isSuccessful()) return second;

        // Soft-fail: leftover dirs are under /tmp and AfterAll releases the engine process.
        System.err.println("jk test: TempDir cleanup incomplete after engine stop for " + rootDir + " (continuing)");
        return DeletionResult.builder(rootDir).build();
    }
}
