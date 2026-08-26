// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * The application jar the augment hands Quarkus as its model's main artifact, and from there into
 * the fast-jar. Raw-jar fingerprints key downstream action caches, so repeated augments over
 * unchanged classes must produce byte-identical jars: entries and manifest alike are pinned by
 * {@link DeterministicZip}, and the compile freshness stamps — whose bodies are a wall clock —
 * stay out.
 */
final class AppJar {

    private AppJar() {}

    /** Jar every file under {@code dir}, stamps excluded. An absent {@code dir} yields a stub. */
    static void write(Path dir, Path jar) throws IOException {
        Manifest man = new Manifest();
        man.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        DeterministicZip zip = DeterministicZip.PINNED;
        try (OutputStream fos = DeterministicZip.archiveStream(jar);
                JarOutputStream jos = new JarOutputStream(fos)) {
            zip.writeManifest(jos, man);
            if (!Files.isDirectory(dir)) return;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = dir.relativize(file).toString().replace('\\', '/');
                    if (BuildStamps.isStampFile(name)) return FileVisitResult.CONTINUE;
                    zip.writeEntry(jos, name, file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
