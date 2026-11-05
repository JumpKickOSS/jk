// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates.testing;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A bare git repository holding the checkout's {@code templates/} tree: the official template
 * catalog as this commit ships it, at a {@code file:} URL, so a test home's engine (a suite's
 * shared sandbox home, or a per-test home of its own) clones its catalog without reaching
 * github.com.
 *
 * <p>Under a sandbox slot ({@link TestHomes}) the repository sits beside the slot's home, named by
 * the content of {@code templates/}: every test JVM of the suite shares it, a green run discards
 * it with the slot, and an edit to a template is a new name. Elsewhere it is built in a temp
 * directory deleted when the JVM exits. The location is fixed by the first call, which {@link
 * SharedHomeTemplateCatalog} makes before any test runs and any overlay moves {@code JK_HOME}.
 */
public final class LocalTemplateCatalog {

    private static @Nullable String url;

    private LocalTemplateCatalog() {}

    /** The {@code file:} URL of the repository, the value of an {@code [templates] official} key. */
    public static synchronized String url() {
        String built = url;
        if (built == null) {
            built = build().toUri().toString();
            url = built;
        }
        return built;
    }

    /** The sandbox slot the tier's {@code JK_HOME} is the home of, when it is one. */
    static Optional<Path> sandboxSlot() {
        Path home = JkDirs.home().toAbsolutePath().normalize();
        Path slot = home.getParent();
        if (slot == null || !home.getFileName().toString().equals("home") || !TestHomes.contains(slot)) {
            return Optional.empty();
        }
        return Optional.of(slot);
    }

    private static Path build() {
        try {
            Path templates = RepoRoot.dir(LocalTemplateCatalog.class, "templates");
            Optional<Path> slot = sandboxSlot();
            if (slot.isEmpty()) {
                Path root = Files.createTempDirectory("jk-template-catalog");
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(() -> PathUtil.deleteRecursively(root), "jk-template-catalog-cleanup"));
                return buildAt(templates, root, root.resolve("catalog.git"));
            }
            Path catalogs = Files.createDirectories(slot.get().resolve("template-catalog"));
            Path bare = catalogs.resolve(contentKey(templates) + ".git");
            if (Files.isRegularFile(bare.resolve("HEAD"))) return bare;
            // Several test JVMs of the suite may build at once: each builds aside and the first
            // rename wins, so no JVM ever clones a half-built repository.
            Path scratch = Files.createTempDirectory(catalogs, "building-");
            try {
                Path built = buildAt(templates, scratch, scratch.resolve("catalog.git"));
                try {
                    Files.move(built, bare, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException lost) {
                    // Another JVM's rename landed first (the target exists and is not empty).
                    if (!Files.isRegularFile(bare.resolve("HEAD"))) throw lost;
                }
            } finally {
                PathUtil.deleteRecursively(scratch);
            }
            return bare;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot build the local template catalog", e);
        }
    }

    private static Path buildAt(Path templates, Path root, Path bare) throws IOException {
        Path work = root.resolve("work");
        copyTree(templates, work);
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
        git(root, "clone", "-q", "--bare", work.toString(), bare.toString());
        return bare;
    }

    /** A digest of every file's path and bytes under {@code dir}, in path order. */
    static String contentKey(Path dir) throws IOException {
        MessageDigest digest = Hashing.newSha256();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(dir.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return Hashing.hex(digest.digest()).substring(0, 16);
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
