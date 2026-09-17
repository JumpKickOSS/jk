// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A jar's contents as a directory the tool reads: every entry extracted under {@code dest}, the
 * previous extraction replaced, an entry whose path escapes the directory refused.
 */
final class Unpack {

    private Unpack() {}

    /** Extract {@code jar} into {@code dest} (emptied first) and answer {@code dest}. */
    static Path extract(Path jar, Path dest) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(dest);
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException(jar.getFileName() + " holds an entry outside the jar: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return root;
    }
}
