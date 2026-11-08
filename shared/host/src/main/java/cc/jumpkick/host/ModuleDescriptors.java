// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * Module descriptors read whatever JDK compiled them. jk's engine runs on its own JDK while a
 * project may compile for a newer one, and {@link ModuleDescriptor#read} refuses a {@code
 * module-info.class} whose class-file version is newer than the running JDK's, though nothing in
 * the {@code Module} attribute it reads depends on that version. The version is capped at the
 * running JDK's before parsing, so a Java 27 module's name, requires and provides read the same on
 * the engine's Java 25 as on 27.
 */
public final class ModuleDescriptors {

    /** The compiled descriptor's file name. */
    public static final String CLASS_FILE = "module-info.class";

    private static final String VERSIONS = "META-INF/versions/";

    /** Class-file major version of Java 1 is 45; feature N is 44 + N. */
    private static final int MAJOR_OFFSET = 44;

    private ModuleDescriptors() {}

    /** Parses a {@code module-info.class}, tolerating a class-file version newer than this JDK's. */
    public static ModuleDescriptor read(byte[] classFile) {
        byte[] bytes = classFile;
        int max = Runtime.version().feature() + MAJOR_OFFSET;
        if (bytes.length >= 8) {
            int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
            if (major > max) {
                bytes = bytes.clone();
                bytes[4] = 0; // minor: a preview minor (0xffff) belongs to the newer release
                bytes[5] = 0;
                bytes[6] = (byte) (max >>> 8);
                bytes[7] = (byte) max;
            }
        }
        return ModuleDescriptor.read(ByteBuffer.wrap(bytes));
    }

    /** The descriptor a classes tree holds, or null when it holds none or it cannot be read. */
    public static @Nullable ModuleDescriptor ofClassesDir(Path dir) {
        Path file = dir.resolve(CLASS_FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            return read(Files.readAllBytes(file));
        } catch (IOException | InvalidModuleDescriptorException unreadable) {
            return null;
        }
    }

    /**
     * The descriptor a jar declares: at its root, else the newest {@code META-INF/versions/<N>/} one
     * of a multi-release jar. Null for a jar without one, an automatic module.
     */
    public static @Nullable ModuleDescriptor ofJar(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry(CLASS_FILE);
            if (entry == null) entry = newestVersioned(jf);
            if (entry == null) return null;
            try (InputStream in = jf.getInputStream(entry)) {
                return read(in.readAllBytes());
            }
        } catch (IOException | InvalidModuleDescriptorException | SecurityException unreadable) {
            return null;
        }
    }

    private static @Nullable JarEntry newestVersioned(JarFile jf) {
        JarEntry best = null;
        int bestRelease = -1;
        for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
            JarEntry entry = e.nextElement();
            String name = entry.getName();
            if (!name.startsWith(VERSIONS) || !name.endsWith("/" + CLASS_FILE)) continue;
            String release = name.substring(VERSIONS.length(), name.length() - CLASS_FILE.length() - 1);
            try {
                int n = Integer.parseInt(release);
                if (n > bestRelease) {
                    bestRelease = n;
                    best = entry;
                }
            } catch (NumberFormatException notARelease) {
                // a directory under versions/ that is not a release number is not a version root
            }
        }
        return best;
    }

    /**
     * The module {@code entry} forms on a module path: a classes tree or jar with a descriptor is
     * that explicit module, a jar without one an automatic module, anything else none.
     */
    public static Optional<ModuleDescriptor> of(Path entry) {
        if (Files.isDirectory(entry)) return Optional.ofNullable(ofClassesDir(entry));
        if (!Files.isRegularFile(entry)) return Optional.empty();
        ModuleDescriptor explicit = ofJar(entry);
        if (explicit != null) return Optional.of(explicit);
        try {
            // No descriptor to parse, so the JDK's own derivation of the automatic name is safe.
            Set<ModuleReference> found = ModuleFinder.of(entry).findAll();
            return found.size() == 1 ? Optional.of(found.iterator().next().descriptor()) : Optional.empty();
        } catch (FindException | SecurityException unreadable) {
            return Optional.empty();
        }
    }
}
