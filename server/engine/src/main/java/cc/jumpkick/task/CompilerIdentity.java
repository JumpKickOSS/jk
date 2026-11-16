// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a JDK's compiler is made of: the content of its {@code jdk.compiler} module, read through
 * the home's own {@code jrt:} image. Two homes that agree on it compile alike whatever their OS,
 * architecture, libc or build host. The module's {@code module-info.class} (it names the target
 * platform) and javac's version resource (it names the vendor build) are left out; every class of
 * the compiler is in. {@link JdkToolchain} puts it beside the vendor and version.
 *
 * <p>Read once per home and image while the engine keeps it: about 1,600 entries, tens of
 * milliseconds.
 */
final class CompilerIdentity {

    private static final String MODULE = "/modules/jdk.compiler";
    private static final List<String> PLATFORM_ENTRIES =
            List.of("module-info.class", "com/sun/tools/javac/resources/version.class");

    /** What a home's compiler identity is read from: its release file and its module image. */
    private record Stamp(String release, StatIdentity image) {}

    private static final StampedMemo<Path, Stamp, @Nullable String> MEMO = StampedMemo.bounded(16);

    private CompilerIdentity() {}

    /**
     * The sha256 of the home's {@code jdk.compiler} module, or null when the home has no module
     * image to read the compiler from. {@code releaseHash} is the content of its release file, which
     * with the image's stat tells a home updated in place from the one read before.
     */
    static @Nullable String digest(Path home, String releaseHash) throws IOException {
        StatIdentity image;
        try {
            image = StatIdentity.of(home.resolve("lib").resolve("modules"));
        } catch (NoSuchFileException noImage) {
            return null;
        }
        try {
            return MEMO.get(home, new Stamp(releaseHash, image), () -> {
                try {
                    return compilerHash(home);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String compilerHash(Path home) throws IOException {
        try (FileSystem jrt = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString()))) {
            Path root = jrt.getPath(MODULE);
            List<Path> entries = new ArrayList<>();
            PathUtil.forEachRegularFile(root, (file, attrs) -> entries.add(file));
            entries.sort(Comparator.comparing(Path::toString));
            MessageDigest md = Hashing.newSha256();
            for (Path entry : entries) {
                String rel = root.relativize(entry).toString();
                if (PLATFORM_ENTRIES.contains(rel)) continue;
                md.update(rel.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                byte[] bytes = Files.readAllBytes(entry);
                md.update(Long.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(bytes);
            }
            return Hashing.hex(md.digest());
        } catch (RuntimeException unreadable) {
            throw new IOException("cannot read the compiler module of " + home + ": " + unreadable, unreadable);
        }
    }
}
