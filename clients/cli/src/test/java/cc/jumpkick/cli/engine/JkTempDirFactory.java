// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * TempDir factory for wire CLI tests (ticket-1052): on cleanup, retry deletes after a short GC
 * pause, then force-stop the resident engine as a last resort so macOS can drop hardlinks under the
 * tree. Lets most tests keep a warm engine across methods without a permanent stop-after-each
 * denylist.
 */
public final class JkTempDirFactory implements TempDirFactory {

    private Path dir;

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws Exception {
        Path root = Files.isDirectory(Path.of("/tmp"))
                ? Path.of("/tmp")
                : Path.of(System.getProperty("java.io.tmpdir"));
        dir = Files.createTempDirectory(root, "jk-junit-");
        return dir;
    }

    @Override
    public void close() throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                deleteRecursively(dir);
                return;
            } catch (IOException e) {
                last = e;
                System.gc();
                try {
                    Thread.sleep(30L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // Last resort: release any engine-held project FDs / hardlinks.
        EngineTestSupport.stopEngineOnly();
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            if (last != null) e.addSuppressed(last);
            // Soft-fail: suite hygiene over hard failure (suite AfterAll also stops the engine).
            System.err.println("jk test: TempDir cleanup incomplete for " + dir + ": " + e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Files.exists(root)) throw e;
        }
    }
}
