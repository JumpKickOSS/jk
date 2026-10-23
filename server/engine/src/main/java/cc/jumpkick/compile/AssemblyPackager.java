// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * Fat/uber (assembly) jar: project classes first (win on conflict), custom manifest, drop
 * signatures and {@code module-info.class}, concat {@code META-INF/services/*} and Boot's
 * {@code AutoConfiguration.imports}, union Spring's Properties-shaped META-INF files per key,
 * packages moved under {@code [application] relocate}, sorted fixed-timestamp entries for
 * reproducibility.
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
            for (PackagedTree.Entry e : request.classes().entries()) {
                String name = e.name();
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                if (BuildStamps.isStampFile(name)) continue; // freshness stamp, not jar content
                Path file = e.file();
                byte[] bytes = e.bytes();
                emitter.emit(
                        name,
                        () -> file != null
                                ? Files.newInputStream(file)
                                : new ByteArrayInputStream(Objects.requireNonNull(bytes)));
            }

            // 2. Dependency jars, in declared order (earlier wins).
            for (Path depJar : request.dependencyJars()) {
                if (!Files.isRegularFile(depJar)) continue;
                try (JarFile jf = new JarFile(depJar.toFile(), false)) {
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
            for (Map.Entry<String, MergedFile> e : emitter.merged.entrySet()) {
                zip.writeParentDirs(jos, e.getKey(), emitter.dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue().render());
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
        final Map<String, MergedFile> merged = new TreeMap<>();

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
                    merged.computeIfAbsent(name, k -> MergedFile.of(originalName))
                            .add(data);
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

    /** One merge file's content across every input that carries it. */
    private sealed interface MergedFile {
        void add(byte[] data) throws IOException;

        byte[] render();

        static MergedFile of(String name) {
            return isPropertiesMergeFile(name) ? new PropertiesUnion() : new Concatenation();
        }
    }

    /** Line-oriented files (SPI registrations, {@code AutoConfiguration.imports}): inputs joined by a newline. */
    private static final class Concatenation implements MergedFile {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public void add(byte[] data) throws IOException {
            if (buf.size() > 0) buf.write('\n');
            buf.write(data);
        }

        @Override
        public byte[] render() {
            return buf.toByteArray();
        }
    }

    /**
     * {@code spring.factories}-shaped files, which Spring reads with {@link Properties}: a key
     * repeated across jars would keep only the last jar's value, so each input is parsed and the
     * comma-separated values are unioned per key, first seen first. Rendered as sorted
     * {@code key=value} lines with Properties escaping, ASCII only.
     */
    private static final class PropertiesUnion implements MergedFile {
        private final Map<String, Set<String>> values = new TreeMap<>();

        @Override
        public void add(byte[] data) throws IOException {
            Properties props = new Properties();
            props.load(new StringReader(new String(data, StandardCharsets.ISO_8859_1)));
            for (String key : props.stringPropertyNames()) {
                Set<String> union = values.computeIfAbsent(key, k -> new LinkedHashSet<>());
                for (String value : props.getProperty(key).split(",")) {
                    String trimmed = value.strip();
                    if (!trimmed.isEmpty()) union.add(trimmed);
                }
            }
        }

        @Override
        public byte[] render() {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Set<String>> e : values.entrySet()) {
                out.append(escape(e.getKey(), true)).append('=');
                boolean first = true;
                for (String value : e.getValue()) {
                    if (!first) out.append(',');
                    out.append(escape(value, false));
                    first = false;
                }
                out.append('\n');
            }
            return out.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        /** {@link Properties#store}'s escaping: separators and comment marks always, spaces in a key or leading. */
        private static String escape(String s, boolean key) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case ' ' -> sb.append(key || i == 0 ? "\\ " : " ");
                    case '\t' -> sb.append("\\t");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\f' -> sb.append("\\f");
                    case '\\', '=', ':', '#', '!' -> sb.append('\\').append(c);
                    default -> {
                        if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04X", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.toString();
        }
    }

    /**
     * Paths merged across project + dependency jars rather than first-wins: SPI files, Boot's
     * auto-configuration imports and Spring's Properties-shaped META-INF registries.
     */
    static boolean isMergeFile(String name) {
        if (name.startsWith("META-INF/services/") && !name.endsWith("/")) return true;
        return isPropertiesMergeFile(name)
                || name.equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    }

    /** The merge files Spring loads as {@link Properties}, where a duplicate key would be last-wins. */
    static boolean isPropertiesMergeFile(String name) {
        return name.equals("META-INF/spring.handlers")
                || name.equals("META-INF/spring.schemas")
                || name.equals("META-INF/spring.factories");
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

    /**
     * Inputs for {@link #packageAssembly(AssemblyRequest)}. {@code relocate} is the {@code
     * [application] relocate} table — source package to shaded package, declaration order — applied
     * by {@link Relocations}; empty bundles every class under its own name.
     */
    public record AssemblyRequest(
            PackagedTree classes,
            List<Path> dependencyJars,
            Path outputJar,
            @Nullable String mainClass,
            Map<String, String> attributes,
            Map<String, byte[]> extraEntries,
            Map<String, String> relocate,
            long timestampEpochSeconds) {

        public AssemblyRequest {
            Objects.requireNonNull(classes, "classes");
            Objects.requireNonNull(outputJar, "outputJar");
            dependencyJars = dependencyJars == null ? List.of() : List.copyOf(dependencyJars);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            extraEntries = extraEntries == null ? Map.of() : Map.copyOf(extraEntries);
            relocate = relocate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(relocate));
        }

        /** The files of one classes directory. */
        public AssemblyRequest(
                Path classesDir,
                List<Path> dependencyJars,
                Path outputJar,
                @Nullable String mainClass,
                Map<String, String> attributes,
                Map<String, byte[]> extraEntries,
                Map<String, String> relocate,
                long timestampEpochSeconds) {
            this(
                    PackagedTree.of(classesDir),
                    dependencyJars,
                    outputJar,
                    mainClass,
                    attributes,
                    extraEntries,
                    relocate,
                    timestampEpochSeconds);
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
