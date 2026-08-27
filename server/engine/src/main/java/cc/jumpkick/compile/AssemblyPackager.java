// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Fat/uber (assembly) jar: project classes first (win on conflict), custom manifest, drop
 * signatures and {@code module-info.class}, concat {@code META-INF/services/*} and common Spring
 * multi-entry META-INF files, sorted fixed-timestamp entries for reproducibility.
 *
 * <p>Enable with {@code [application] assembly = true} (or {@code jk assemble}). See
 * {@code docs/features/packaging.md}.
 */
public final class AssemblyPackager {

    public Path packageAssembly(AssemblyRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);

        Set<String> written = new HashSet<>();
        // Directory entries synthesized for SoftServiceLoader (Micronaut assembly).
        Set<String> dirs = new HashSet<>();
        // Multi-entry META-INF files merged across inputs; TreeMap → deterministic.
        Map<String, ByteArrayOutputStream> merged = new TreeMap<>();
        DeterministicZip zip = new DeterministicZip(request.timestampEpochSeconds());

        try (OutputStream out = DeterministicZip.archiveStream(request.outputJar());
                JarOutputStream jos = new JarOutputStream(out)) {
            zip.writeManifest(jos, manifest);
            written.add("META-INF/MANIFEST.MF");

            // 1. Project classes + resources win.
            List<Path> files = collectFiles(request.classesDir());
            files.sort(Comparator.comparing(p -> normalize(request.classesDir(), p)));
            for (Path file : files) {
                String name = normalize(request.classesDir(), file);
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                if (BuildStamps.isStampFile(name)) continue; // freshness stamp, not jar content
                if (isExcluded(name)) continue;
                if (isMergeFile(name)) {
                    accumulate(merged, name, Files.readAllBytes(file));
                    continue;
                }
                if (written.add(name)) {
                    zip.writeParentDirs(jos, name, dirs);
                    // Streamed — a large bundled resource never has to fit in the heap.
                    zip.writeEntryStreaming(jos, name, Files.newInputStream(file));
                }
            }

            // 2. Dependency jars, in declared order (earlier wins).
            for (Path depJar : request.dependencyJars()) {
                if (!Files.isRegularFile(depJar)) continue;
                try (JarFile jf = new JarFile(depJar.toFile())) {
                    List<JarEntry> entries = new ArrayList<>();
                    jf.stream().filter(e -> !e.isDirectory()).forEach(entries::add);
                    entries.sort(Comparator.comparing(JarEntry::getName));
                    for (JarEntry e : entries) {
                        String name = e.getName();
                        if (name.equals("META-INF/MANIFEST.MF") || isExcluded(name)) continue;
                        if (isMergeFile(name)) {
                            // Tiny multi-entry files — buffer for the cross-jar merge.
                            try (InputStream in = jf.getInputStream(e)) {
                                accumulate(merged, name, in.readAllBytes());
                            }
                            continue;
                        }
                        if (written.add(name)) {
                            zip.writeParentDirs(jos, name, dirs);
                            // Streamed entry-to-entry copy — never buffers a whole entry.
                            zip.writeEntryStreaming(jos, name, jf.getInputStream(e));
                        }
                    }
                }
            }

            // 3. Merged multi-entry META-INF files (services, Spring handlers, …).
            for (Map.Entry<String, ByteArrayOutputStream> e : merged.entrySet()) {
                zip.writeParentDirs(jos, e.getKey(), dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue().toByteArray());
                written.add(e.getKey());
            }

            // 4. Generated entries (e.g. the CycloneDX SBOM); real content wins on collision.
            for (Map.Entry<String, byte[]> e : new TreeMap<>(request.extraEntries()).entrySet()) {
                if (!written.add(e.getKey())) continue;
                zip.writeParentDirs(jos, e.getKey(), dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue());
            }
        }
        return request.outputJar();
    }

    private static void accumulate(Map<String, ByteArrayOutputStream> sink, String name, byte[] data)
            throws IOException {
        ByteArrayOutputStream buf = sink.computeIfAbsent(name, k -> new ByteArrayOutputStream());
        if (buf.size() > 0) buf.write('\n');
        buf.write(data);
    }

    /**
     * Paths concatenated across project + dependency jars. SPI files plus common
     * Spring multi-value META-INF entries that break when first-wins.
     */
    static boolean isMergeFile(String name) {
        if (name.startsWith("META-INF/services/") && !name.endsWith("/")) return true;
        return name.equals("META-INF/spring.handlers")
                || name.equals("META-INF/spring.schemas")
                || name.equals("META-INF/spring.factories")
                || name.equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    }

    /** Signature blocks, JPMS module descriptors from deps, and other fat-jar poison. */
    static boolean isExcluded(String name) {
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) return true;
        if (!name.startsWith("META-INF/")) return false;
        // INDEX.LIST indexes ONE jar's packages; inherited into a fat jar it lies about every
        // merged entry and some loaders trust it over scanning. Shadow and Shade both drop it.
        if (name.equals("META-INF/INDEX.LIST")) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    private static Manifest buildManifest(AssemblyRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (request.mainClass() != null && !request.mainClass().isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, request.mainClass());
        }
        for (Map.Entry<String, String> e : request.attributes().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            attrs.put(new Attributes.Name(e.getKey()), e.getValue());
        }
        return manifest;
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        // Files.find, not walk+isRegularFile: the walk already read each entry's attributes, and
        // re-resolving every path to ask again is the dominant cost of packaging a large tree.
        try (Stream<Path> stream = Files.find(root, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
            stream.forEach(result::add);
        }
        return result;
    }

    private static String normalize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /** Inputs for {@link #packageAssembly(AssemblyRequest)}. */
    public record AssemblyRequest(
            Path classesDir,
            List<Path> dependencyJars,
            Path outputJar,
            String mainClass,
            Map<String, String> attributes,
            Map<String, byte[]> extraEntries,
            long timestampEpochSeconds) {

        public AssemblyRequest {
            Objects.requireNonNull(classesDir, "classesDir");
            Objects.requireNonNull(outputJar, "outputJar");
            dependencyJars = dependencyJars == null ? List.of() : List.copyOf(dependencyJars);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            extraEntries = extraEntries == null ? Map.of() : Map.copyOf(extraEntries);
        }

        /** Back-compat constructor: no generated (non-filesystem) entries. */
        public AssemblyRequest(
                Path classesDir,
                List<Path> dependencyJars,
                Path outputJar,
                String mainClass,
                Map<String, String> attributes,
                long timestampEpochSeconds) {
            this(classesDir, dependencyJars, outputJar, mainClass, attributes, Map.of(), timestampEpochSeconds);
        }
    }
}
