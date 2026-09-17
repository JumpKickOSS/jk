// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Packages a Spring Boot executable jar ({@code JarLauncher} layout: loader, {@code
 * BOOT-INF/classes}, STORED nested libs, classpath/layers index). Sorted entries + fixed timestamps.
 */
public final class BootJarPackager {

    /** Boot's launcher entry point (the loader-classes package moved to {@code .launch} in 3.2). */
    public static final String JAR_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";

    private static final String CLASSES_PREFIX = "BOOT-INF/classes/";
    private static final String LIB_PREFIX = "BOOT-INF/lib/";
    private static final String CLASSPATH_IDX = "BOOT-INF/classpath.idx";
    private static final String LAYERS_IDX = "BOOT-INF/layers.idx";
    private static final String LOADER_PREFIX = "org/";

    /** The loader's service registrations that ride along with its classes ({@code nested:} URLs). */
    private static final String SERVICES_PREFIX = "META-INF/services/";

    /**
     * Manifest {@code Spring-Boot-Jar-Type} values Boot's Gradle and Maven plugins leave out of
     * {@code BOOT-INF/lib}: a starter is a POM with a manifest, a processor runs at compile time,
     * and devtools is never meant to ship.
     */
    static final Set<String> EXCLUDED_JAR_TYPES =
            Set.of("annotation-processor", "dependencies-starter", "development-tool");

    private static final String JAR_TYPE_ATTRIBUTE = "Spring-Boot-Jar-Type";

    /** Classpath locations Boot's own readers use ({@code BuildProperties}, the sbom actuator). */
    static final String SBOM_ENTRY = CLASSES_PREFIX + "META-INF/sbom/application.cdx.json";

    public Path packageBootJar(BootJarRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);
        DeterministicZip zip = new DeterministicZip(request.timestampEpochSeconds());
        Set<String> dirsWritten = new HashSet<>();

        try (OutputStream out = DeterministicZip.archiveStream(request.outputJar());
                JarOutputStream jos = new JarOutputStream(out)) {
            // The archive's own META-INF directory entry comes first, as Boot's plugins write it.
            zip.writeDir(jos, "META-INF/", dirsWritten);
            zip.writeManifest(jos, manifest);

            // 1. Loader classes exploded at the root — java -jar must find
            //    Main-Class before anything else is resolvable.
            explodeLoader(jos, request.loaderJar(), zip, dirsWritten);

            // 2. Application classes + resources under BOOT-INF/classes/, then any AOT
            //    output roots (generated classes + GraalVM hint resources) — app files win
            //    on collision, AOT roots in the given order after that.
            Set<String> classEntries = new HashSet<>();
            List<Path> roots = new ArrayList<>();
            roots.add(request.classesDir());
            roots.addAll(request.aotDirs());
            for (Path root : roots) {
                List<Path> files = collectFiles(root);
                files.sort(Comparator.comparing(p -> normalize(root, p)));
                for (Path file : files) {
                    String name = normalize(root, file);
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    if (BuildStamps.isStampFile(name)) continue;
                    if (!classEntries.add(name)) continue;
                    zip.writeParentDirs(jos, CLASSES_PREFIX + name, dirsWritten);
                    zip.writeEntryStreaming(jos, CLASSES_PREFIX + name, Files.newInputStream(file));
                }
            }

            // 3. Boot-read metadata under BOOT-INF/classes/META-INF (classpath-visible: the sbom
            //    actuator resolves it as a resource; build-info.properties arrives with the classes).
            if (request.sbom().length > 0) {
                zip.writeParentDirs(jos, SBOM_ENTRY, dirsWritten);
                zip.writeEntry(jos, SBOM_ENTRY, request.sbom());
            }

            // 4. Nested dependency jars — STORED with a precomputed CRC. Jars whose manifest
            //    declares a type Boot does not nest are dropped here and from both indexes.
            List<Lib> nested = nestable(request.libs());
            zip.writeDir(jos, LIB_PREFIX, dirsWritten);
            for (Lib lib : nested) {
                zip.writeStored(jos, LIB_PREFIX + lib.fileName(), lib.jar());
            }

            // 5. The two index files the manifest points at.
            zip.writeEntry(jos, CLASSPATH_IDX, classpathIndex(nested));
            zip.writeEntry(jos, LAYERS_IDX, layersIndex(nested));
        }
        return request.outputJar();
    }

    /** The libs that belong under {@code BOOT-INF/lib}, in the given order. */
    private static List<Lib> nestable(List<Lib> libs) throws IOException {
        List<Lib> out = new ArrayList<>(libs.size());
        for (Lib lib : libs) {
            if (nests(lib.jar())) out.add(lib);
        }
        return out;
    }

    /**
     * Whether Boot's own plugins would nest {@code jar}: yes unless its manifest's {@code
     * Spring-Boot-Jar-Type} is one of {@link #EXCLUDED_JAR_TYPES}. Reads the manifest only.
     */
    static boolean nests(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) return true;
            String type = manifest.getMainAttributes().getValue(JAR_TYPE_ATTRIBUTE);
            return type == null || !EXCLUDED_JAR_TYPES.contains(type);
        }
    }

    /** {@code classpath.idx}: one {@code - "BOOT-INF/lib/…jar"} line per nested jar, in order. */
    private static byte[] classpathIndex(List<Lib> libs) {
        StringBuilder sb = new StringBuilder();
        for (Lib lib : libs) {
            sb.append("- \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code layers.idx} in Boot's default layer order (least- to most-frequently changing):
     * dependencies, spring-boot-loader, snapshot-dependencies, application. Empty layers still
     * appear — {@code jarmode tools list-layers} prints every declared layer.
     */
    private static byte[] layersIndex(List<Lib> libs) {
        StringBuilder sb = new StringBuilder();
        sb.append("- \"dependencies\":\n");
        for (Lib lib : libs) {
            if (!lib.snapshot())
                sb.append("  - \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        sb.append("- \"spring-boot-loader\":\n");
        sb.append("  - \"").append(LOADER_PREFIX).append("\"\n");
        sb.append("- \"snapshot-dependencies\":\n");
        for (Lib lib : libs) {
            if (lib.snapshot())
                sb.append("  - \"").append(LIB_PREFIX).append(lib.fileName()).append("\"\n");
        }
        sb.append("- \"application\":\n");
        sb.append("  - \"BOOT-INF/classes/\"\n");
        sb.append("  - \"").append(CLASSPATH_IDX).append("\"\n");
        sb.append("  - \"").append(LAYERS_IDX).append("\"\n");
        sb.append("  - \"META-INF/\"\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Copy the loader jar's classes and its {@code META-INF/services} registrations to the boot
     * jar's root, exactly the entries Boot's own plugins copy. The launcher registers a {@code
     * nested:} filesystem through {@code java.nio.file.spi.FileSystemProvider}, so that service
     * file is load-bearing; the loader's manifest, licence and notice are not and are dropped.
     */
    private static void explodeLoader(
            JarOutputStream jos, Path loaderJar, DeterministicZip zip, Set<String> dirsWritten) throws IOException {
        try (JarFile jf = new JarFile(loaderJar.toFile())) {
            List<JarEntry> entries = new ArrayList<>();
            jf.stream().filter(e -> !e.isDirectory()).forEach(entries::add);
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry e : entries) {
                String name = e.getName();
                if (!name.endsWith(".class") && !name.startsWith(SERVICES_PREFIX)) continue;
                zip.writeParentDirs(jos, name, dirsWritten);
                zip.writeEntryStreaming(jos, name, jf.getInputStream(e));
            }
        }
    }

    private static Manifest buildManifest(BootJarRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, JAR_LAUNCHER);
        attrs.put(new Attributes.Name("Start-Class"), request.startClass());
        attrs.put(new Attributes.Name("Spring-Boot-Version"), request.bootVersion());
        attrs.put(new Attributes.Name("Spring-Boot-Classes"), CLASSES_PREFIX);
        attrs.put(new Attributes.Name("Spring-Boot-Lib"), LIB_PREFIX);
        attrs.put(new Attributes.Name("Spring-Boot-Classpath-Index"), CLASSPATH_IDX);
        attrs.put(new Attributes.Name("Spring-Boot-Layers-Index"), LAYERS_IDX);
        if (request.sbom().length > 0) {
            attrs.put(new Attributes.Name("Sbom-Format"), "CycloneDX");
            attrs.put(new Attributes.Name("Sbom-Location"), SBOM_ENTRY);
        }
        for (Map.Entry<String, String> e : request.attributes().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            attrs.put(new Attributes.Name(e.getKey()), e.getValue());
        }
        return manifest;
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        // // Attributes from the walk instead of a stat per entry (via's owner).
        PathUtil.forEachRegularFile(root, (file, attrs) -> result.add(file));
        return result;
    }

    private static String normalize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * One nested jar: its {@code BOOT-INF/lib} file name (original {@code artifact-version.jar},
     * never a CAS hash), the file to copy, whether it lands in the {@code snapshot-dependencies}
     * layer, and the coordinate group that disambiguates it from a same-named artifact in another
     * group ({@code ""} for a workspace sibling, which has no coordinate).
     */
    public record Lib(String fileName, Path jar, boolean snapshot, String group) {

        public Lib {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(jar, "jar");
            group = group == null ? "" : group;
        }
    }

    /**
     * Inputs for {@link #packageBootJar(BootJarRequest)}.
     *
     * @param sbom CycloneDX JSON bytes (see the engine's {@code CycloneDxSbom}); empty = no SBOM
     * @param aotDirs Spring AOT output roots (generated classes / hint resources) merged into
     *     {@code BOOT-INF/classes} after the app's own files
     */
    public record BootJarRequest(
            Path classesDir,
            List<Lib> libs,
            Path loaderJar,
            Path outputJar,
            String startClass,
            String bootVersion,
            Map<String, String> attributes,
            byte[] sbom,
            List<Path> aotDirs,
            long timestampEpochSeconds) {

        public BootJarRequest {
            Objects.requireNonNull(classesDir, "classesDir");
            Objects.requireNonNull(loaderJar, "loaderJar");
            Objects.requireNonNull(outputJar, "outputJar");
            Objects.requireNonNull(startClass, "startClass");
            requireResolvedVersion(bootVersion);
            libs = libs == null ? List.of() : dedupeFileNames(libs);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            aotDirs = aotDirs == null ? List.of() : List.copyOf(aotDirs);
        }

        /**
         * {@code Spring-Boot-Version} is a version a consumer reads, so a selector may not reach
         * it. The declared {@code version} key is a selector — {@code latest}, {@code ^4},
         * {@code =4.1.0} — and writing it verbatim shipped jars announcing
         * {@code Spring-Boot-Version: latest}. The resolved closure is the only admissible source
         * ({@link BootJarInputs}); this refuses the spellings that are unambiguously selectors.
         *
         * <p>It cannot refuse all of them: a bare major line ({@code 4}) is a caret floor to the
         * resolver and a legal Maven version to everyone else, and no shape check can separate the
         * two. That is precisely why the value must be resolved rather than validated.
         */
        private static void requireResolvedVersion(String bootVersion) {
            Objects.requireNonNull(bootVersion, "bootVersion");
            if (bootVersion.isBlank() || bootVersion.equals("latest") || !bootVersion.matches("[\\w.+-]+")) {
                throw new IllegalArgumentException("Spring-Boot-Version must be a resolved version, not the selector \""
                        + bootVersion + "\" — read it off the runtime closure");
            }
        }

        /**
         * Two coordinates can share an {@code artifact-version.jar} name across groups; nested
         * entries must be unique, so later collisions get their coordinate group prepended —
         * {@code com.example.b-util-1.0.jar}. The group is the fact that actually distinguishes
         * them and the only one a reader can act on. A workspace sibling has no coordinate, and a
         * group can in principle repeat a file name (two classifiers of one artifact), so an
         * ordinal follows as the last resort: a counter that reads like a counter.
         *
         * <p>Never the jar's parent directory. Entries are served out of the content-addressed
         * store, so that directory is two hex characters of a SHA-256 — unique enough by accident,
         * meaningless to a reader, and content-derived, so bumping either colliding dependency
         * renamed a nested entry and churned both index files.
         */
        private static List<Lib> dedupeFileNames(List<Lib> libs) {
            Set<String> seen = new LinkedHashSet<>();
            List<Lib> out = new ArrayList<>(libs.size());
            for (Lib lib : libs) {
                Lib effective = lib;
                if (!seen.add(lib.fileName())) {
                    String prefixed =
                            lib.group().isEmpty() ? "dup-" + lib.fileName() : lib.group() + "-" + lib.fileName();
                    int n = 2;
                    String candidate = prefixed;
                    while (!seen.add(candidate)) candidate = prefixed + "." + n++;
                    effective = new Lib(candidate, lib.jar(), lib.snapshot(), lib.group());
                }
                out.add(effective);
            }
            return List.copyOf(out);
        }
    }
}
