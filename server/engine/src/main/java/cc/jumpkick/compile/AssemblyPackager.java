// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Fat/uber (assembly) jar: project classes first (win on conflict), custom manifest, drop
 * signatures and {@code module-info.class}, concat {@code META-INF/services/*} and common Spring
 * multi-entry META-INF files, packages moved under {@code [application] relocate}, sorted
 * fixed-timestamp entries for reproducibility.
 *
 * <p>Enable with {@code [application] assembly = true} (or {@code jk assemble}). See
 * {@code docs/user/packaging.md}.
 */
public final class AssemblyPackager {

    public Path packageAssembly(AssemblyRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);
        DeterministicZip zip = new DeterministicZip(request.timestampEpochSeconds());

        try (OutputStream out = DeterministicZip.archiveStream(request.outputJar());
                JarOutputStream jos = new JarOutputStream(out)) {
            zip.writeManifest(jos, manifest);
            Emitter emitter = new Emitter(jos, zip, new Relocations(request.relocate()));
            emitter.written.add("META-INF/MANIFEST.MF");

            // 1. Project classes + resources win.
            List<Path> files = collectFiles(request.classesDir());
            files.sort(Comparator.comparing(p -> normalize(request.classesDir(), p)));
            for (Path file : files) {
                String name = normalize(request.classesDir(), file);
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                if (BuildStamps.isStampFile(name)) continue; // freshness stamp, not jar content
                emitter.emit(name, () -> Files.newInputStream(file));
            }

            // 2. Dependency jars, in declared order (earlier wins).
            for (Path depJar : request.dependencyJars()) {
                if (!Files.isRegularFile(depJar)) continue;
                try (JarFile jf = new JarFile(depJar.toFile())) {
                    List<JarEntry> entries = new ArrayList<>();
                    jf.stream().filter(e -> !e.isDirectory()).forEach(entries::add);
                    entries.sort(Comparator.comparing(JarEntry::getName));
                    for (JarEntry e : entries) {
                        if (e.getName().equals("META-INF/MANIFEST.MF")) continue;
                        emitter.emit(e.getName(), () -> jf.getInputStream(e));
                    }
                }
            }

            // 3. Merged multi-entry META-INF files (services, Spring handlers, …).
            for (Map.Entry<String, ByteArrayOutputStream> e : emitter.merged.entrySet()) {
                zip.writeParentDirs(jos, e.getKey(), emitter.dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue().toByteArray());
                emitter.written.add(e.getKey());
            }

            // 4. Generated entries (e.g. the CycloneDX SBOM); real content wins on collision.
            for (Map.Entry<String, byte[]> e : new TreeMap<>(request.extraEntries()).entrySet()) {
                if (!emitter.written.add(e.getKey())) continue;
                zip.writeParentDirs(jos, e.getKey(), emitter.dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue());
            }
        }
        return request.outputJar();
    }

    /** Opens one input entry's bytes; the caller closes the stream. */
    @FunctionalInterface
    private interface EntrySource {
        InputStream open() throws IOException;
    }

    /**
     * One entry at a time into the archive under the merge, exclusion and relocation rules: a
     * merge file is buffered for the cross-jar concatenation, a class under a relocation rule is
     * rewritten, everything else streams through, first writer of a name winning.
     */
    private static final class Emitter {
        private final JarOutputStream jos;
        private final DeterministicZip zip;
        private final Relocations relocations;
        final Set<String> written = new HashSet<>();
        /** Directory entries synthesized for SoftServiceLoader (Micronaut assembly). */
        final Set<String> dirs = new HashSet<>();
        /** Multi-entry META-INF files merged across inputs; TreeMap → deterministic. */
        final Map<String, ByteArrayOutputStream> merged = new TreeMap<>();

        Emitter(JarOutputStream jos, DeterministicZip zip, Relocations relocations) {
            this.jos = jos;
            this.zip = zip;
            this.relocations = relocations;
        }

        void emit(String originalName, EntrySource source) throws IOException {
            if (isExcluded(originalName)) return;
            String name = relocations.relocateEntry(originalName);
            if (isMergeFile(originalName)) {
                // Tiny multi-entry files — buffer for the cross-jar merge.
                try (InputStream in = source.open()) {
                    byte[] data = in.readAllBytes();
                    if (originalName.startsWith("META-INF/services/")) {
                        data = relocations
                                .relocateServices(new String(data, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    accumulate(merged, name, data);
                }
                return;
            }
            if (!written.add(name)) return;
            zip.writeParentDirs(jos, name, dirs);
            if (!relocations.isEmpty() && originalName.endsWith(".class")) {
                try (InputStream in = source.open()) {
                    zip.writeEntry(jos, name, relocations.relocateClass(in.readAllBytes()));
                }
                return;
            }
            // Streamed — a large bundled resource never has to fit in the heap.
            try (InputStream in = source.open()) {
                zip.writeEntryStreaming(jos, name, in);
            }
        }
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

    /**
     * Signature blocks, JPMS module descriptors from deps, per-dependency Maven metadata, and other
     * fat-jar poison. {@code META-INF/LICENSE*} and {@code META-INF/NOTICE*} are never excluded:
     * they are a redistribution obligation for most bundled libraries.
     */
    static boolean isExcluded(String name) {
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) return true;
        if (!name.startsWith("META-INF/")) return false;
        // INDEX.LIST indexes ONE jar's packages; inherited into a fat jar it lies about every
        // merged entry and some loaders trust it over scanning. Shadow and Shade both drop it.
        if (name.equals("META-INF/INDEX.LIST")) return true;
        // META-INF/maven/<g>/<a>/pom.{xml,properties} describes how ONE dependency was built. In a
        // fat jar it names a coordinate the jar is not, carries no licence text and no
        // redistribution obligation, and nothing at runtime reads it — up to half a percent of
        // every assembly for no reader.
        if (name.startsWith("META-INF/maven/")) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    /** The same manifest a thin jar gets, in the same attribute order; see {@link JarPackager#manifest}. */
    private static Manifest buildManifest(AssemblyRequest request) {
        return JarPackager.manifest(request.mainClass(), request.attributes());
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

    /**
     * Inputs for {@link #packageAssembly(AssemblyRequest)}. {@code relocate} is the {@code
     * [application] relocate} table — source package to shaded package, declaration order — applied
     * by {@link Relocations}; empty bundles every class under its own name.
     */
    public record AssemblyRequest(
            Path classesDir,
            List<Path> dependencyJars,
            Path outputJar,
            @Nullable String mainClass,
            Map<String, String> attributes,
            Map<String, byte[]> extraEntries,
            Map<String, String> relocate,
            long timestampEpochSeconds) {

        public AssemblyRequest {
            Objects.requireNonNull(classesDir, "classesDir");
            Objects.requireNonNull(outputJar, "outputJar");
            dependencyJars = dependencyJars == null ? List.of() : List.copyOf(dependencyJars);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            extraEntries = extraEntries == null ? Map.of() : Map.copyOf(extraEntries);
            relocate = relocate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(relocate));
        }

        /** No package relocation. */
        public AssemblyRequest(
                Path classesDir,
                List<Path> dependencyJars,
                Path outputJar,
                @Nullable String mainClass,
                Map<String, String> attributes,
                Map<String, byte[]> extraEntries,
                long timestampEpochSeconds) {
            this(
                    classesDir,
                    dependencyJars,
                    outputJar,
                    mainClass,
                    attributes,
                    extraEntries,
                    Map.of(),
                    timestampEpochSeconds);
        }

        /** No generated (non-filesystem) entries and no package relocation. */
        public AssemblyRequest(
                Path classesDir,
                List<Path> dependencyJars,
                Path outputJar,
                @Nullable String mainClass,
                Map<String, String> attributes,
                long timestampEpochSeconds) {
            this(
                    classesDir,
                    dependencyJars,
                    outputJar,
                    mainClass,
                    attributes,
                    Map.of(),
                    Map.of(),
                    timestampEpochSeconds);
        }
    }
}
