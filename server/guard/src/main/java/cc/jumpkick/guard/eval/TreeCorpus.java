// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The files the tree lane's text rules read, for the lane's key: the same walk, so a file the rules
 * read is a file the key covers ({@code .jk/} and a {@code target} package under {@code src/}
 * included) and nothing the rules skip moves it.
 */
public final class TreeCorpus {

    private TreeCorpus() {}

    /** One corpus file. */
    @FunctionalInterface
    public interface Visit {
        void visit(String rel, Path file, BasicFileAttributes attrs) throws IOException;
    }

    /** Visit every corpus file under {@code root} with its {@code /}-separated relative path. */
    public static void walk(Path root, Visit visit) throws IOException {
        TextFiles.walk(root, visit::visit);
    }
}
