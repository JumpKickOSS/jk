// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's static cone: the class with every class nested in it, then every workspace class
 * reachable from them, each named by its bytes' digest. A class reaches another when its class file
 * names it anywhere: a class entry of its constant pool, a field or method descriptor, a signature,
 * an annotation, or a string constant spelling a workspace class's binary name. The walk stops at
 * the workspace's edge; what lies past it (the JDK, dependency jars) is keyed whole elsewhere.
 *
 * <p>A cone that names {@code java.util.ServiceLoader} takes in every provider a workspace service
 * file lists. A cone that names a classpath-scanning entry point (Spring's test context, Quarkus,
 * Micronaut, ArchUnit's importer, ClassGraph, Reflections, CDI) says so: what such a framework loads
 * no reference shows, so the class is keyed by its whole module.
 */
public final class ClassCones {

    private ClassCones() {}

    /** One class file: its bytes' digest, the workspace-or-not names it refers to, and a scanner it names. */
    record ClassFile(
            String digest, Set<String> refs, @Nullable String scanner) {}

    /**
     * A test class's static cone.
     *
     * @param digest every class of the cone by name and bytes, as one digest
     * @param classes the cone's classes, binary names
     * @param scanner the first classpath-scanning entry point the cone names, or null
     */
    public record Cone(
            String digest, Set<String> classes, @Nullable String scanner) {}

    /** Internal-name prefixes of the entry points whose loads no reference shows. */
    static final List<String> SCANNERS = List.of(
            "org/springframework/boot/test/",
            "org/springframework/test/context/",
            "org/springframework/context/annotation/ComponentScan",
            "io/quarkus/test/",
            "io/micronaut/test/",
            "io/helidon/microprofile/testing/",
            "com/tngtech/archunit/core/importer/",
            "io/github/classgraph/",
            "org/reflections/",
            "org/jboss/weld/",
            "jakarta/enterprise/inject/se/");

    private static final String SERVICE_LOADER = "java/util/ServiceLoader";
    private static final String SERVICES = "META-INF/services/";

    /** The classes of a set of roots, the first root defining a name winning, as the class path does. */
    public static final class Index {
        private final Map<String, ClassFile> classes;
        private final Set<String> providers;

        Index(Map<String, ClassFile> classes, Set<String> providers) {
            this.classes = classes;
            this.providers = providers;
        }

        /** Every class of the roots by name and bytes, as one digest: the cone of a class keyed by its whole module. */
        public String digest() {
            Map<String, String> all = new TreeMap<>();
            classes.forEach((name, cf) -> all.put(name, cf.digest()));
            MessageDigest md = Hashing.newSha256();
            for (Map.Entry<String, String> e : all.entrySet()) {
                md.update((e.getKey() + ' ' + e.getValue() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return Hashing.hex(md.digest());
        }

        /** How many classes the roots hold. */
        public int size() {
            return classes.size();
        }

        /** Whether {@code binaryName} is a class of the roots. */
        public boolean contains(String binaryName) {
            return classes.containsKey(internal(binaryName));
        }

        /** The static cone of the top-level class {@code binaryName}. */
        public Cone cone(String binaryName) {
            String top = internal(binaryName);
            Deque<String> todo = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            todo.add(top);
            String nested = top + "$";
            for (String name : classes.keySet()) if (name.startsWith(nested)) todo.add(name);
            String scanner = null;
            boolean serviceLoader = false;
            while (!todo.isEmpty()) {
                String name = todo.poll();
                if (!seen.add(name)) continue;
                ClassFile cf = classes.get(name);
                if (cf == null) continue;
                if (scanner == null) scanner = cf.scanner();
                for (String ref : cf.refs()) {
                    if (ref.equals(SERVICE_LOADER) && !serviceLoader) {
                        serviceLoader = true;
                        todo.addAll(providers);
                    }
                    if (classes.containsKey(ref) && !seen.contains(ref)) todo.add(ref);
                }
            }
            Map<String, String> cone = new TreeMap<>();
            for (String name : seen) {
                ClassFile cf = classes.get(name);
                if (cf != null) cone.put(name.replace('/', '.'), cf.digest());
            }
            MessageDigest md = Hashing.newSha256();
            for (Map.Entry<String, String> e : cone.entrySet()) {
                md.update((e.getKey() + ' ' + e.getValue() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return new Cone(
                    Hashing.hex(md.digest()), cone.keySet(), scanner == null ? null : scanner.replace('/', '.'));
        }
    }

    /** The classes under {@code roots}, directories of class files and jars, in class-path order. */
    public static Index index(List<Path> roots) {
        Map<String, ClassFile> classes = new LinkedHashMap<>();
        Set<String> providers = new TreeSet<>();
        for (Path root : roots) {
            try {
                if (Files.isDirectory(root)) readTree(root, classes, providers);
                else if (Files.isRegularFile(root)) readJar(root, classes, providers);
            } catch (IOException | RuntimeException unreadable) {
                Log.debug("class cones: " + root + " unreadable", unreadable);
            }
        }
        return new Index(classes, providers);
    }

    private static void readTree(Path root, Map<String, ClassFile> into, Set<String> providers) throws IOException {
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (f, attrs) -> files.add(f));
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith(SERVICES)) {
                providers(Files.readString(f, StandardCharsets.UTF_8), providers);
                continue;
            }
            if (!rel.endsWith(".class") || rel.endsWith("module-info.class")) continue;
            String name = rel.substring(0, rel.length() - ".class".length());
            if (!into.containsKey(name)) into.put(name, read(Files.readAllBytes(f)));
        }
    }

    private static void readJar(Path jar, Map<String, ClassFile> into, Set<String> providers) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.startsWith(SERVICES)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        providers(new String(in.readAllBytes(), StandardCharsets.UTF_8), providers);
                    }
                    continue;
                }
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) continue;
                String name = n.substring(0, n.length() - ".class".length());
                if (into.containsKey(name)) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    into.put(name, read(in.readAllBytes()));
                }
            }
        }
    }

    private static void providers(String serviceFile, Set<String> into) {
        for (String line : serviceFile.split("\n")) {
            int hash = line.indexOf('#');
            String name = (hash < 0 ? line : line.substring(0, hash)).strip();
            if (!name.isEmpty()) into.add(internal(name));
        }
    }

    private static final Pattern DESCRIPTOR_NAME = Pattern.compile("L([\\w$]+(?:/[\\w$]+)*)[;<]");
    private static final Pattern BINARY_NAME = Pattern.compile("[\\w$]+(?:\\.[\\w$]+)+");

    /** One class file's digest and every name its constant pool spells, by class entry, descriptor or string. */
    static ClassFile read(byte[] bytes) {
        Set<String> refs = new HashSet<>();
        int count = u2(bytes, 8);
        Map<Integer, String> utf8 = new HashMap<>();
        List<Integer> classEntries = new ArrayList<>();
        int at = 10;
        for (int i = 1; i < count; i++) {
            int tag = bytes[at] & 0xff;
            switch (tag) {
                case 1 -> {
                    int len = u2(bytes, at + 1);
                    utf8.put(i, new String(bytes, at + 3, len, StandardCharsets.UTF_8));
                    at += 3 + len;
                }
                case 7 -> {
                    classEntries.add(u2(bytes, at + 1));
                    at += 3;
                }
                case 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                case 5, 6 -> {
                    at += 9;
                    i++;
                }
                default -> throw new IllegalArgumentException("constant pool tag " + tag);
            }
        }
        for (int index : classEntries) {
            String name = utf8.get(index);
            if (name == null) continue;
            if (name.startsWith("[")) addDescriptorNames(name, refs);
            else refs.add(name);
        }
        for (String s : utf8.values()) {
            addDescriptorNames(s, refs);
            if (s.indexOf('.') > 0 && BINARY_NAME.matcher(s).matches()) refs.add(internal(s));
        }
        // The platform's own names reach nothing of the workspace; leaving them out keeps a sibling's
        // memoized classes small. The service loader stays: a cone naming it takes in the providers.
        refs.removeIf(r ->
                (r.startsWith("java/") || r.startsWith("jdk/") || r.startsWith("sun/")) && !r.equals(SERVICE_LOADER));
        String scanner = null;
        for (String ref : refs) {
            for (String prefix : SCANNERS) {
                if (ref.startsWith(prefix) && (scanner == null || ref.compareTo(scanner) < 0)) scanner = ref;
            }
        }
        Set<String> kept = new HashSet<>();
        for (String r : refs) kept.add(r.intern());
        return new ClassFile(Hashing.sha256Hex(bytes), Set.copyOf(kept), scanner);
    }

    private static void addDescriptorNames(String s, Collection<String> into) {
        if (s.indexOf('L') < 0 || (s.indexOf(';') < 0 && s.indexOf('<') < 0)) return;
        Matcher m = DESCRIPTOR_NAME.matcher(s);
        while (m.find()) into.add(m.group(1));
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }
}
