// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** The commit a checkout's {@code HEAD} names, read from {@code .git} without running git. */
final class GitHead {

    private GitHead() {}

    static @Nullable String read(Path root) {
        try {
            Path git = root.resolve(".git");
            if (Files.isRegularFile(git)) {
                String pointer = Files.readString(git).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                git = root.resolve(pointer.substring("gitdir:".length()).strip())
                        .normalize();
            }
            Path head = git.resolve("HEAD");
            if (!Files.isRegularFile(head)) return null;
            String ref = Files.readString(head).strip();
            if (!ref.startsWith("ref:")) return ref;
            String name = ref.substring(4).strip();
            Path common = commonDir(git);
            for (Path dir : new Path[] {git, common}) {
                Path loose = dir.resolve(name);
                if (Files.isRegularFile(loose)) return Files.readString(loose).strip();
            }
            Path packed = common.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed)) {
                    if (line.endsWith(" " + name)) return line.substring(0, line.indexOf(' '));
                }
            }
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** A worktree's shared git directory ({@code commondir}), else the directory itself. */
    private static Path commonDir(Path git) throws IOException {
        Path pointer = git.resolve("commondir");
        return Files.isRegularFile(pointer)
                ? git.resolve(Files.readString(pointer).strip()).normalize()
                : git;
    }
}
