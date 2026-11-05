// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A bare git repository holding the checkout's {@code templates/} tree: the official template
 * catalog as this commit ships it, at a {@code file:} URL, so an {@link IsolatedHome} engine clones
 * its catalog without reaching github.com.
 *
 * <p>Built once per test JVM, on first use, and deleted when the JVM exits.
 */
final class LocalTemplateCatalog {

    private static @Nullable String url;

    private LocalTemplateCatalog() {}

    /** The {@code file:} URL of the repository, the value of an {@code [templates] official} key. */
    static synchronized String url() {
        String built = url;
        if (built == null) {
            built = build();
            url = built;
        }
        return built;
    }

    private static String build() {
        try {
            Path root = Files.createTempDirectory("jk-template-catalog");
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(() -> PathUtil.deleteRecursively(root), "jk-template-catalog-cleanup"));
            Path work = root.resolve("work");
            copyTree(RepoRoot.dir(LocalTemplateCatalog.class, "templates"), work);
            git(work, "init", "-q", "-b", "main");
            git(work, "add", ".");
            git(
                    work,
                    "-c",
                    "user.name=jk",
                    "-c",
                    "user.email=jk@localhost",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-q",
                    "-m",
                    "catalog");
            Path bare = root.resolve("catalog.git");
            git(root, "clone", "-q", "--bare", work.toString(), bare.toString());
            return bare.toUri().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot build the local template catalog", e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** {@code git args} in {@code cwd}. */
    private static void git(Path cwd, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            int exit = pb.start().waitFor();
            if (exit != 0) throw new IOException("git " + String.join(" ", args) + " exited " + exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: git " + String.join(" ", args), e);
        }
    }
}
