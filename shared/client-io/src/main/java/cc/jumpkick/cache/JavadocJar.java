// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.PathUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builds a Maven-style {@code <artifact>-<version>-javadoc.jar}: the tree javadoc wrote, or — for
 * a module with no Java sources — a jar holding only {@link #README} saying why it is empty. Maven
 * Central accepts either. Deterministic like {@link SourcesJar}: sorted entries, pinned mtimes.
 */
public final class JavadocJar {

    /** The one entry of a javadoc jar for a module javadoc cannot document. */
    public static final String README = "README";

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private JavadocJar() {}

    /** Zip the javadoc output tree ({@code index.html}, {@code element-list}, …) in memory. */
    public static byte[] fromTree(Path docRoot) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            ZIP.writeManifest(jos, manifest());
            List<Path> entries = new ArrayList<>();
            PathUtil.forEachRegularFile(docRoot, (file, attrs) -> entries.add(file));
            Collections.sort(entries);
            for (Path file : entries) {
                ZIP.writeEntry(jos, docRoot.relativize(file).toString().replace('\\', '/'), file);
            }
        }
        return baos.toByteArray();
    }

    /** A jar whose only entry is a {@code README} explaining that {@code reason} left nothing to document. */
    public static byte[] readmeOnly(String reason) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            ZIP.writeManifest(jos, manifest());
            String text = "This javadoc jar is intentionally empty.\n\n" + reason + "\n";
            ZIP.writeEntry(jos, README, text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    private static Manifest manifest() {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "jk");
        return mf;
    }
}
