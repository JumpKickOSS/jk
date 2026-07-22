// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
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
 *   <li>If still stuck, best-effort walk-delete then return a successful empty result so the suite
 *       continues (leftovers under {@code /tmp} are ephemeral).
 * </ol>
 */
public final class JkTempDirDeletionStrategy implements TempDirDeletionStrategy {

    @Override
    public DeletionResult delete(
            Path rootDir, AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        try {
            DeletionResult first = Standard.INSTANCE.delete(rootDir, elementContext, extensionContext);
            if (first.isSuccessful()) return first;

            EngineTestSupport.stopEngineOnly();
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            DeletionResult second = Standard.INSTANCE.delete(rootDir, elementContext, extensionContext);
            if (second.isSuccessful()) return second;

            // Best-effort force: CAS hardlinks / still-open FDs can leave macOS unable to unlink.
            forceDeleteQuietly(rootDir);
            System.err.println(
                    "jk test: TempDir cleanup incomplete after engine stop for " + rootDir + " (continuing)");
        } catch (Throwable t) {
            // Never let TempDir hygiene fail the suite (strategy load / engine stop / FS races).
            System.err.println("jk test: TempDir cleanup error for " + rootDir + ": " + t + " (continuing)");
            forceDeleteQuietly(rootDir);
        }
        // Empty failures → isSuccessful() true regardless of leftover paths.
        return DeletionResult.builder(rootDir).build();
    }

    private static void forceDeleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored2) {
                    }
                });
            } catch (IOException ignored2) {
            }
        }
    }
}
