// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import cc.jumpkick.surface.DynamicSurface;
import cc.jumpkick.surface.NativeImageMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Classes a jar names <em>as text</em> rather than referencing from bytecode, in the two
 * conventions the JVM ecosystem uses:
 *
 * <ul>
 *   <li><b>Service files</b> — {@code META-INF/services/<interface>}, one implementation FQCN per
 *       line, {@code #} comments allowed.
 *   <li><b>Marker indexes</b> — {@code META-INF/<vendor>/<interface>/<impl>}, where the leaf path
 *       segment <em>is</em> the class name and the file itself is usually empty. Micronaut's
 *       {@code META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/...} is the common
 *       case; the shape is a convention, not one framework's invention.
 * </ul>
 *
 * <p>Static reachability analysis cannot see through either — nothing in the bytecode contains
 * these strings — so R8 discards the classes unless told to keep them. Everything both the keep
 * derivation and the post-shrink audit need starts here.
 */
final class ByNameIndex {

    /** Leaf segments with these suffixes are data files sitting beside an index, not class names. */
    private static final Set<String> NON_CLASS_SUFFIXES =
            Set.of(".json", ".xml", ".properties", ".txt", ".md", ".yml", ".yaml", ".proto", ".sql");

    private ByNameIndex() {}

    /** Every class named by a service file or marker index across {@code jars}. */
    static Set<String> referencedClasses(Collection<Path> jars) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (name.startsWith("META-INF/services/")) {
                        readServiceFile(jf, entry, out);
                    } else {
                        markerClassName(name).ifPresent(out::add);
                    }
                }
            }
        }
        return out;
    }

    /**
     * The GraalVM metadata {@code jars} publish under {@code META-INF/native-image}, as a surface.
     *
     * <p>Libraries already describe their own reflective surface there for {@code native-image},
     * which finds it on the classpath unaided. R8 has no equivalent, so the same facts have to
     * reach it as keep rules. Reading them costs a jar scan and no application run.
     */
    static DynamicSurface composedFromLibraries(Collection<Path> jars) throws IOException {
        DynamicSurface surface = DynamicSurface.empty();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!NativeImageMetadata.isMetadataFile(entry.getName())) continue;
                    String body;
                    try (var in = jf.getInputStream(entry)) {
                        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    surface = surface.merge(NativeImageMetadata.parse(entry.getName(), body, "library"));
                }
            }
        }
        return surface;
    }

    /** Fully-qualified names of every class present in {@code jars}. */
    static Set<String> classesIn(Collection<Path> jars) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".class")) {
                        out.add(name.substring(0, name.length() - ".class".length())
                                .replace('/', '.'));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The class name a {@code META-INF/<vendor>/<interface>/<impl>} marker encodes, or empty when
     * {@code name} is not that shape. Exactly four segments, and both the interface and the
     * implementation segment must read as fully-qualified class names — which is what separates a
     * marker index from a directory of data files.
     */
    static java.util.Optional<String> markerClassName(String name) {
        if (!name.startsWith("META-INF/")) return java.util.Optional.empty();
        String[] parts = name.split("/");
        if (parts.length != 4) return java.util.Optional.empty();
        if (!isClassName(parts[2]) || !isClassName(parts[3])) return java.util.Optional.empty();
        return java.util.Optional.of(parts[3]);
    }

    /** How many distinct classes R8 reported absent from the program inputs. */
    static int countMissingClasses(String r8Output) {
        if (r8Output == null || r8Output.isEmpty()) return 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : r8Output.split("\n")) {
            int at = line.indexOf("Missing class ");
            if (at < 0) continue;
            String rest = line.substring(at + "Missing class ".length()).trim();
            int space = rest.indexOf(' ');
            seen.add(space < 0 ? rest : rest.substring(0, space));
        }
        return seen.size();
    }

    /**
     * The classes {@code jars} name by text, as a {@link DynamicSurface}. Every entry is a
     * {@link DynamicSurface.Kind#SERVICE_IMPLEMENTATION}: an implementation an index points at,
     * which the emitter turns into a class-level keep.
     */
    static DynamicSurface surface(Collection<String> names) {
        List<DynamicSurface.Entry> entries = new ArrayList<>(names.size());
        for (String name : names) {
            entries.add(DynamicSurface.Entry.type(DynamicSurface.Kind.SERVICE_IMPLEMENTATION, name, "index"));
        }
        return new DynamicSurface(entries);
    }

    private static void readServiceFile(JarFile jf, JarEntry entry, Set<String> sink) throws IOException {
        String body;
        try (var in = jf.getInputStream(entry)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : body.split("\n")) {
            String candidate = line.split("#", 2)[0].trim();
            if (isClassName(candidate)) sink.add(candidate);
        }
    }

    /**
     * A dotted name whose every segment is a legal Java identifier. Deliberately strict: a false
     * positive becomes a keep rule for a class that does not exist (harmless) or an audit failure
     * for one that never existed (not harmless).
     */
    static boolean isClassName(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.indexOf('.') < 0) return false;
        for (String suffix : NON_CLASS_SUFFIXES) {
            if (candidate.endsWith(suffix)) return false;
        }
        int segmentStart = 0;
        for (int i = 0; i <= candidate.length(); i++) {
            if (i == candidate.length() || candidate.charAt(i) == '.') {
                if (i == segmentStart) return false; // empty segment: leading, trailing or doubled dot
                if (!Character.isJavaIdentifierStart(candidate.charAt(segmentStart))) return false;
                segmentStart = i + 1;
                continue;
            }
            if (!Character.isJavaIdentifierPart(candidate.charAt(i))) return false;
        }
        return true;
    }
}
