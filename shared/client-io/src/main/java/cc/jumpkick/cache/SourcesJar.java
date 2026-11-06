// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.TreeContent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds a Maven-style {@code <artifact>-<version>-sources.jar} by zipping the project's source
 * directories. Entries are stored relative to each root ({@code src/main/java/com/foo/Bar.java}
 * becomes {@code com/foo/Bar.java} in the archive). Deterministic: entries are sorted and every
 * mtime — the manifest's included — is pinned by {@link DeterministicZip}.
 */
public final class SourcesJar {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private SourcesJar() {}

    /**
     * Build a sources jar in memory from the given source roots. Empty or absent roots are skipped;
     * a path two roots both hold (a {@code package-info.java} in the Java and the Scala root) is
     * taken from the first.
     */
    public static byte[] build(List<Path> sourceRoots) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "jk");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // NOT `new JarOutputStream(baos, mf)`: the convenience constructor stamps the manifest
        // entry with System.currentTimeMillis(), so every rebuild would churn the first entry.
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            ZIP.writeManifest(jos, mf);
            Set<String> written = new HashSet<>();
            for (Path root : sourceRoots) {
                if (!Files.isDirectory(root)) continue;
                appendTree(jos, root, written);
            }
        }
        return baos.toByteArray();
    }

    private static void appendTree(JarOutputStream jos, Path root, Set<String> written) throws IOException {
        // The files the step's key fingerprints each root by: its content files, in name order.
        Map<String, Path> entries = new TreeMap<>();
        TreeContent.forEach(root, (name, file, attrs) -> entries.put(name, file));
        for (Map.Entry<String, Path> e : entries.entrySet()) {
            if (written.add(e.getKey())) ZIP.writeEntry(jos, e.getKey(), e.getValue());
        }
    }
}
