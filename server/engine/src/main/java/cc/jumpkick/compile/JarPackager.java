// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

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

            Set<String> written = new HashSet<>();
            Set<String> dirs = new HashSet<>();
            // Sorted by name, for a deterministic entry order.
            for (PackagedTree.Entry e : request.tree().entries()) {
                String name = e.name();
                if (name.equals("META-INF/MANIFEST.MF")) continue; // already written
                if (BuildStamps.isStampFile(name)) continue; // build-host artefact, not jar content
                written.add(name);
                zip.writeParentDirs(jos, name, dirs);
                if (e.file() != null) zip.writeEntry(jos, name, e.file());
                else zip.writeEntry(jos, name, Objects.requireNonNull(e.bytes()));
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
        return manifest(request.mainClass(), request.attributes());
    }

    /**
     * The manifest every jk-packaged jar carries: {@code Manifest-Version}, then {@code Main-Class}
     * when there is one, then the custom attributes (the {@code [manifest]} table, the SBOM
     * pointers) in name order.
     *
     * <p>The order is part of the jar's bytes, so it has to be a function of the attributes alone.
     * The request records hold their attributes in an immutable copy whose iteration order is
     * salted per JVM: two engines packaging the same classes would otherwise write the same
     * attributes in two orders and produce two jars — and jk's own install, which keys packaging
     * on the engine that runs it, would then never reach a fixed point. Sorting by name settles
     * the order without asking the caller what map it passed.
     */
    static Manifest manifest(@Nullable String mainClass, Map<String, String> attributes) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        for (Map.Entry<String, String> e : new TreeMap<>(attributes).entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            attrs.put(new Attributes.Name(e.getKey()), e.getValue());
        }
        return manifest;
    }

    /** Input to {@link JarPackager#packageJar(JarRequest)}. */
    public record JarRequest(
            PackagedTree tree,
            Path outputJar,
            @Nullable String mainClass,
            long timestampEpochSeconds,
            Map<String, String> attributes,
            Map<String, byte[]> extraEntries) {

        public JarRequest {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(outputJar, "outputJar");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            extraEntries = extraEntries == null ? Map.of() : Map.copyOf(extraEntries);
        }

        /** The files of one directory. */
        public JarRequest(
                Path inputDir,
                Path outputJar,
                @Nullable String mainClass,
                long timestampEpochSeconds,
                Map<String, String> attributes,
                Map<String, byte[]> extraEntries) {
            this(PackagedTree.of(inputDir), outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }

        /** No generated (non-filesystem) entries. */
        public JarRequest(
                Path inputDir,
                Path outputJar,
                @Nullable String mainClass,
                long timestampEpochSeconds,
                Map<String, String> attributes) {
            this(inputDir, outputJar, mainClass, timestampEpochSeconds, attributes, Map.of());
        }

        /** No custom manifest attributes and no generated entries. */
        public JarRequest(Path inputDir, Path outputJar, String mainClass, long timestampEpochSeconds) {
            this(inputDir, outputJar, mainClass, timestampEpochSeconds, Map.of(), Map.of());
        }

        public static JarRequest of(Path inputDir, Path outputJar) {
            return of(PackagedTree.of(inputDir), outputJar);
        }

        public static JarRequest of(PackagedTree tree, Path outputJar) {
            return new JarRequest(tree, outputJar, null, 0L, Map.of(), Map.of());
        }

        public JarRequest withMainClass(String mainClass) {
            return new JarRequest(tree, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }

        /** Custom jar-manifest attributes from the project's {@code [manifest]} table. */
        public JarRequest withAttributes(Map<String, String> attributes) {
            return new JarRequest(tree, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }

        /** Generated entries (path → bytes) written after the filesystem walk, e.g. the SBOM. */
        public JarRequest withExtraEntries(Map<String, byte[]> extraEntries) {
            return new JarRequest(tree, outputJar, mainClass, timestampEpochSeconds, attributes, extraEntries);
        }
    }
}
