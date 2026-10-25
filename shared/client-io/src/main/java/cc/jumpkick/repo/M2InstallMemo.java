// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The record that jk installed a project's jar and POM into a Maven local repository, kept under
 * the jk store and never in the repository itself. It names the coordinate and the sha256 of the two
 * files as jk wrote them; a slot whose files still hold those bytes is jk's to replace when its
 * version is a snapshot, and any other slot is somebody else's.
 *
 * <p>Three newline-delimited lines: {@code g:a:v}, the jar sha256, the POM sha256. One memo per
 * repository root and coordinate, so an install redirected with {@code --m2-dir} never claims a
 * slot in the machine's repository.
 *
 * @param jarSha256 lower-case hex of the thin jar jk wrote
 * @param pomSha256 lower-case hex of the POM jk wrote
 */
public record M2InstallMemo(String coordinate, String jarSha256, String pomSha256) {

    private static final String TREE = "m2-installs";

    public M2InstallMemo {
        Objects.requireNonNull(coordinate, "coordinate");
        jarSha256 = jarSha256.strip().toLowerCase(Locale.ROOT);
        pomSha256 = pomSha256.strip().toLowerCase(Locale.ROOT);
    }

    /** The memo file for {@code coord} installed under {@code repository}, in the jk store at {@code storeRoot}. */
    public static Path file(Path storeRoot, Path repository, Coordinate coord) {
        String root = Hashing.sha256Hex(
                        repository.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))
                .substring(0, 12);
        return storeRoot
                .resolve(TREE)
                .resolve(root)
                .resolve(coord.group().replace('.', '/'))
                .resolve(coord.artifact())
                .resolve(coord.version())
                .resolve("install.jk");
    }

    public static Optional<M2InstallMemo> read(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 3) return Optional.empty();
            String jar = lines.get(1).strip();
            String pom = lines.get(2).strip();
            if (lines.get(0).isBlank() || jar.length() != 64 || pom.length() != 64) return Optional.empty();
            return Optional.of(new M2InstallMemo(lines.get(0).strip(), jar, pom));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, coordinate + "\n" + jarSha256 + "\n" + pomSha256 + "\n");
    }

    /**
     * True when every file this memo covers that exists on disk still holds the bytes jk wrote:
     * the slot has not been touched since. A file that is absent does not disown the slot.
     */
    public boolean owns(@Nullable String jarShaOnDisk, @Nullable String pomShaOnDisk) {
        return (jarShaOnDisk == null || jarShaOnDisk.equalsIgnoreCase(jarSha256))
                && (pomShaOnDisk == null || pomShaOnDisk.equalsIgnoreCase(pomSha256));
    }
}
