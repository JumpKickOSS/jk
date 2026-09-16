// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The Java packages a jar provides: every directory that holds a {@code .class} entry outside
 * {@code META-INF}, as a dotted name. A jar's answer is written once under {@code indexDir} as
 * {@code <sha256>.txt}, one package per line, so a lock's jars are listed once per store, not once
 * per diagnostic.
 */
public final class PackageIndex {

    /** The store directory the per-jar package lists live under. */
    public static final String DIR = "package-index";

    private PackageIndex() {}

    /**
     * The packages of {@code jar}. {@code sha256} is the lock's checksum for it, hex without the
     * {@code sha256:} prefix, and names the cache file; {@code null} lists the jar without caching.
     * An unreadable jar provides nothing.
     */
    public static Set<String> packagesOf(Path jar, @Nullable String sha256, Path indexDir) {
        Path cached = sha256 == null || sha256.isBlank() ? null : indexDir.resolve(sha256 + ".txt");
        if (cached != null) {
            Set<String> hit = read(cached);
            if (hit != null) return hit;
        }
        Set<String> packages = list(jar);
        if (cached != null) write(cached, packages);
        return packages;
    }

    /** The packages a lock row's checksum names — {@code sha256:<hex>} — or the whole text when unprefixed. */
    public static @Nullable String hex(@Nullable String checksum) {
        if (checksum == null || checksum.isBlank()) return null;
        int colon = checksum.indexOf(':');
        return colon < 0 ? checksum : checksum.substring(colon + 1);
    }

    static Set<String> list(Path jar) {
        Set<String> packages = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                int slash = name.lastIndexOf('/');
                if (slash <= 0) continue;
                packages.add(name.substring(0, slash).replace('/', '.'));
            }
        } catch (IOException unreadable) {
            return Set.of();
        }
        return packages;
    }

    private static @Nullable Set<String> read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Set<String> out = new TreeSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) out.add(line.strip());
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Written beside then moved into place, so a reader never sees a half list. */
    private static void write(Path file, Set<String> packages) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(tmp, List.copyOf(packages), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // The list is a cache; the next lookup lists the jar again.
        }
    }
}
