// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>The index is compact: each name once, each class's references as an array of name numbers and
 * its digest as two longs, so a module with tens of thousands of workspace classes on its classpath
 * costs a few megabytes ({@link #BYTES_PER_CLASS}).
 */
public final class ClassCones {

    private ClassCones() {}

    /**
     * The heap an index costs per class it holds: its name, its digest, its reference numbers (some
     * forty, past the platform's own names) and its share of the name table.
     */
    public static final long BYTES_PER_CLASS = 512;

    /**
     * A test class's static cone.
     *
     * @param digest every class of the cone by name and bytes, as one digest
     * @param size how many classes the cone holds
     * @param scanner the first classpath-scanning entry point the cone names, or null
     */
    public record Cone(String digest, int size, @Nullable String scanner, Index index, BitSet members) {

        /** Whether the class {@code binaryName} lies in the cone. */
        public boolean contains(String binaryName) {
            Integer id = index.ids.get(internal(binaryName));
            return id != null && members.get(id);
        }

        /** The cone's classes by binary name, sorted. */
        public Set<String> classes() {
            Set<String> out = new TreeSet<>();
            for (int i = members.nextSetBit(0); i >= 0; i = members.nextSetBit(i + 1)) {
                out.add(index.names.get(i).replace('/', '.'));
            }
            return out;
        }
    }

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
    private static final int[] NONE = new int[0];

    /** The classes of a set of roots, the first root defining a name winning, as the class path does. */
    public static final class Index {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> names = new ArrayList<>();
        /** By name number: the class's reference numbers, or null for a name no root defines. */
        private final List<int @Nullable []> refs = new ArrayList<>();

        private long[] digests = new long[64];
        private int[] scanners = new int[16];
        private final List<Integer> providers = new ArrayList<>();
        private final int loader;
        private int defined;

        private final int most;
        private boolean full;

        Index(int most) {
            this.most = most;
            loader = id(SERVICE_LOADER);
        }

        /** Whether the roots held more classes than the index may: it then names only part of them. */
        public boolean full() {
            return full;
        }

        private int id(String internalName) {
            Integer known = ids.get(internalName);
            if (known != null) return known;
            int id = names.size();
            ids.put(internalName, id);
            names.add(internalName);
            refs.add(null);
            if (2 * id + 1 >= digests.length) digests = Arrays.copyOf(digests, digests.length * 2);
            if (id >= scanners.length) scanners = Arrays.copyOf(scanners, scanners.length * 2);
            scanners[id] = -1;
            return id;
        }

        private boolean defines(int id) {
            return refs.get(id) != null;
        }

        private void define(String internalName, byte[] bytes) {
            if (defined >= most) {
                full = true;
                return;
            }
            int id = id(internalName);
            if (defines(id)) return;
            ClassFile cf = read(bytes);
            int[] out = new int[cf.refs().size()];
            int n = 0;
            for (String r : cf.refs()) out[n++] = id(r);
            refs.set(id, out.length == 0 ? NONE : out);
            ByteBuffer d = ByteBuffer.wrap(cf.digest());
            digests[2 * id] = d.getLong();
            digests[2 * id + 1] = d.getLong();
            scanners[id] = cf.scanner() == null ? -1 : id(cf.scanner());
            defined++;
        }

        /** How many classes the roots hold. */
        public int size() {
            return defined;
        }

        /** Whether {@code binaryName} is a class of the roots. */
        public boolean contains(String binaryName) {
            Integer id = ids.get(internal(binaryName));
            return id != null && defines(id);
        }

        /** Every class of the roots by name and bytes, as one digest: the cone of a class keyed by its whole module. */
        public String digest() {
            BitSet all = new BitSet(names.size());
            for (int i = 0; i < names.size(); i++) if (defines(i)) all.set(i);
            return digestOf(all);
        }

        /** The static cone of the top-level class {@code binaryName}. */
        public Cone cone(String binaryName) {
            return cone(binaryName, List.of());
        }

        /**
         * The static cone of the top-level class {@code binaryName} together with each class of
         * {@code also} (a class it was seen to load that no reference shows) and what those reach.
         */
        public Cone cone(String binaryName, Collection<String> also) {
            String top = internal(binaryName);
            BitSet seen = new BitSet(names.size());
            int[] todo = new int[64 + also.size()];
            int head = 0;
            int tail = 0;
            Integer topId = ids.get(top);
            if (topId != null) todo[tail++] = topId;
            for (int i : nestedOf(top)) {
                if (tail == todo.length) todo = Arrays.copyOf(todo, todo.length * 2);
                todo[tail++] = i;
            }
            for (String a : also) {
                Integer id = ids.get(internal(a));
                if (id == null) continue;
                if (tail == todo.length) todo = Arrays.copyOf(todo, todo.length * 2);
                todo[tail++] = id;
            }
            int scanner = -1;
            boolean serviceLoader = false;
            while (head < tail) {
                int id = todo[head++];
                if (seen.get(id)) continue;
                int[] out = refs.get(id);
                if (out == null) continue;
                seen.set(id);
                if (scanner < 0) scanner = scanners[id];
                for (int r : out) {
                    if (r == loader && !serviceLoader) {
                        serviceLoader = true;
                        for (int p : providers) {
                            if (tail == todo.length) todo = Arrays.copyOf(todo, todo.length * 2);
                            todo[tail++] = p;
                        }
                    }
                    if (!seen.get(r) && defines(r)) {
                        if (tail == todo.length) todo = Arrays.copyOf(todo, todo.length * 2);
                        todo[tail++] = r;
                    }
                }
            }
            return new Cone(
                    digestOf(seen),
                    seen.cardinality(),
                    scanner < 0 ? null : names.get(scanner).replace('/', '.'),
                    this,
                    seen);
        }

        /** By top-level class, the numbers of the classes nested in it, grouped once on the first ask. */
        private @Nullable Map<String, List<Integer>> nested;

        private List<Integer> nestedOf(String top) {
            Map<String, List<Integer>> by = nested;
            if (by == null) {
                by = new HashMap<>();
                for (int i = 0; i < names.size(); i++) {
                    String n = names.get(i);
                    int dollar = n.indexOf('$');
                    if (dollar > 0 && defines(i))
                        by.computeIfAbsent(n.substring(0, dollar), k -> new ArrayList<>())
                                .add(i);
                }
                nested = by;
            }
            return by.getOrDefault(top, List.of());
        }

        private String digestOf(BitSet members) {
            // Sorted by name, so the digest is one whatever order the roots were read in.
            List<Integer> order = new ArrayList<>(members.cardinality());
            for (int i = members.nextSetBit(0); i >= 0; i = members.nextSetBit(i + 1)) order.add(i);
            order.sort((a, b) -> names.get(a).compareTo(names.get(b)));
            MessageDigest md = Hashing.newSha256();
            ByteBuffer two = ByteBuffer.allocate(16);
            for (int id : order) {
                md.update(names.get(id).getBytes(StandardCharsets.UTF_8));
                two.clear();
                two.putLong(digests[2 * id]).putLong(digests[2 * id + 1]);
                md.update(two.array());
            }
            return Hashing.hex(md.digest());
        }

        private void provider(String binaryName) {
            providers.add(id(internal(binaryName)));
        }
    }

    /** One class file: its bytes' digest, the names it refers to past the platform's own, and a scanner it names. */
    record ClassFile(
            byte[] digest, Set<String> refs, @Nullable String scanner) {}

    /** The classes under {@code roots}, directories of class files and jars, in class-path order. */
    public static Index index(List<Path> roots) {
        return index(roots, Integer.MAX_VALUE);
    }

    /**
     * As {@link #index(List)}, holding at most {@code most} classes: past it the index is {@link
     * Index#full() full}, names only part of the roots, and no cone over it is to be trusted.
     */
    public static Index index(List<Path> roots, int most) {
        Index index = new Index(most);
        for (Path root : roots) {
            try {
                if (Files.isDirectory(root)) readTree(root, index);
                else if (Files.isRegularFile(root)) readJar(root, index);
            } catch (IOException | RuntimeException unreadable) {
                Log.debug("class cones: " + root + " unreadable", unreadable);
            }
        }
        return index;
    }

    private static void readTree(Path root, Index index) throws IOException {
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (f, attrs) -> files.add(f));
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith(SERVICES)) {
                providers(Files.readString(f, StandardCharsets.UTF_8), index);
                continue;
            }
            if (!rel.endsWith(".class") || rel.endsWith("module-info.class")) continue;
            String name = rel.substring(0, rel.length() - ".class".length());
            Integer id = index.ids.get(name);
            if (id == null || !index.defines(id)) index.define(name, Files.readAllBytes(f));
        }
    }

    private static void readJar(Path jar, Index index) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.startsWith(SERVICES)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        providers(new String(in.readAllBytes(), StandardCharsets.UTF_8), index);
                    }
                    continue;
                }
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) continue;
                String name = n.substring(0, n.length() - ".class".length());
                Integer id = index.ids.get(name);
                if (id != null && index.defines(id)) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    index.define(name, in.readAllBytes());
                }
            }
        }
    }

    private static void providers(String serviceFile, Index index) {
        for (String line : serviceFile.split("\n")) {
            int hash = line.indexOf('#');
            String name = (hash < 0 ? line : line.substring(0, hash)).strip();
            if (!name.isEmpty()) index.provider(name);
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
        // The platform's own names reach nothing of the workspace; leaving them out keeps the index
        // small. The service loader stays: a cone naming it takes in the providers.
        refs.removeIf(r ->
                (r.startsWith("java/") || r.startsWith("jdk/") || r.startsWith("sun/")) && !r.equals(SERVICE_LOADER));
        String scanner = null;
        for (String ref : refs) {
            for (String prefix : SCANNERS) {
                if (ref.startsWith(prefix) && (scanner == null || ref.compareTo(scanner) < 0)) scanner = ref;
            }
        }
        return new ClassFile(Hashing.sha256(bytes), refs, scanner);
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
