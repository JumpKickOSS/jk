// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Packages classes + resources into a reproducible jar: sorted entries, {@code SOURCE_DATE_EPOCH}
 * timestamps, minimal manifest (no {@code Created-By}/{@code Build-Jdk}).
 */
public final class JarPackager {

    public Path packageJar(JarRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);

        try (OutputStream out = DeterministicZip.archiveStream(request.outputJar());
                JarOutputStream jos = new JarOutputStream(out)) {
            DeterministicZip zip = new DeterministicZip(request.timestampEpochSeconds());
            zip.writeManifest(jos, manifest);

            List<Path> files = collectFiles(request.inputDir());
            // Sort by relative path for deterministic entry order.
            files.sort(Comparator.comparing(p -> normalize(request.inputDir(), p)));

            Set<String> written = new HashSet<>();
            Set<String> dirs = new HashSet<>();
            for (Path file : files) {
                String name = normalize(request.inputDir(), file);
                if (name.equals("META-INF/MANIFEST.MF")) continue; // already written
                if (BuildStamps.isStampFile(name)) continue; // build-host artefact, not jar content
                written.add(name);
                zip.writeParentDirs(jos, name, dirs);
                zip.writeEntry(jos, name, file);
            }

            // Generated entries (e.g. the CycloneDX SBOM) — sorted for reproducibility;
            // filesystem content wins on a path collision.
            for (Map.Entry<String, byte[]> e : new TreeMap<>(request.extraEntries()).entrySet()) {
                if (written.contains(e.getKey())) continue;
                zip.writeParentDirs(jos, e.getKey(), dirs);
                zip.writeEntry(jos, e.getKey(), e.getValue());
            }
        }
        return request.outputJar();
    }

    private static Manifest buildManifest(JarRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (request.mainClass() != null && !request.mainClass().isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, request.mainClass());
        }
        // Custom attributes from the [manifest] table (Implementation-*, etc.).
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

    /** Input to {@link JarPackager#packageJar(JarRequest)}. */
    public record JarRequest(
            Path inputDir,
            Path outputJar,
            String mainClass,
            long timestampEpochSeconds,
            Map<String, String> attributes,
            Map<String, byte[]> extraEntries) {

        public JarRequest {
            Objects.requireNonNull(inputDir, "inputDir");
            Objects.requireNonNull(outputJar, "outputJar");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            extraEntries = extraEntries == null ? Map.of() : Map.copyOf(extraEntries);
        }

        /** No generated (non-filesystem) entries. */
        public JarRequest(
                Path inputDir,
                Path outputJar,
                String mainClass,
                long timestampEpochSeconds,
                Map<String, String> attributes) {
            this(inputDir, outputJar, mainClass, timestampEpochSeconds, attributes, Map.of());
        }

        /** No custom manifest attributes and no generated entries. */
        public JarRequest(Path inputDir, Path outputJar, String mainClass, long timestampEpochSeconds) {
            this(inputDir, outputJar, mainClass, timestampEpochSeconds, Map.of(), Map.of());
        }

        public static JarRequest of(Path inputDir, Path outputJar) {
            return new JarRequest(inputDir, outputJar, null, 0L, Map.of(), Map.of());
        }

        public JarRequest withMainClass(String mainClass) {
            return new JarRequest(inputDir, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }

        /** Custom jar-manifest attributes from the project's {@code [manifest]} table. */
        public JarRequest withAttributes(Map<String, String> attributes) {
            return new JarRequest(inputDir, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }

        /** Generated entries (path → bytes) written after the filesystem walk, e.g. the SBOM. */
        public JarRequest withExtraEntries(Map<String, byte[]> extraEntries) {
            return new JarRequest(inputDir, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }
    }
}
