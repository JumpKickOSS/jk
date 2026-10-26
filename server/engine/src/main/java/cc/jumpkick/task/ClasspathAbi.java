// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Whole-entry JVM ABI token for a jar or classes directory. {@code abi:<sha256>} of the sorted
 * per-class API lines, digested as they are read so no jar's API text is ever held whole; {@code missing:<module-relative path>} when the path is absent. Memoized on {@link
 * ClasspathFingerprint#entry} so the second sighting of the same bytes is a lookup. Does not switch
 * compile action keys.
 */
public final class ClasspathAbi {

    private static final AtomicLong EXTRACTS = new AtomicLong();

    /** Memo keys carry the extractor's format, so a memo filled by an earlier {@link ClassAbi} is a miss. */
    private static final String MEMO_NAMESPACE = "abi." + ClassAbi.FORMAT + ":";

    private ClasspathAbi() {}

    public static String token(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            return "missing:" + PortablePath.of(abs);
        }
        if (!attrs.isRegularFile() && !attrs.isDirectory()) {
            return "missing:" + PortablePath.of(abs);
        }
        String identity = ClasspathFingerprint.entry(abs);
        return token(abs, attrs, identity);
    }

    private static String token(Path abs, BasicFileAttributes attrs, String identity) throws IOException {
        if (identity.startsWith("missing:")) return identity;
        String hit = AbiMemo.get(MEMO_NAMESPACE + identity);
        if (hit != null) return hit;
        EXTRACTS.incrementAndGet();
        // The lines are digested as they are extracted, one class at a time, never held together:
        // a compiler-sized jar has an API text of hundreds of megabytes, and several modules key
        // their classpaths at once on a cold memo. The digest is over the same bytes a join would
        // have produced — each line, "\n" between lines — so the token is the same word.
        Lines digest = new Lines();
        extract(abs, attrs, digest);
        String abi = "abi:" + digest.hex();
        AbiMemo.put(MEMO_NAMESPACE + identity, abi);
        return abi;
    }

    /**
     * Both {@link #token} and {@link #resources} from one {@link ClasspathFingerprint#entry} walk when
     * {@code cp} is {@link ClasspathAbi#token}.
     */
    public static String tokenWithResources(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            String missing = "missing:" + PortablePath.of(abs);
            return missing + "+res:" + missing;
        }
        if (!attrs.isRegularFile() && !attrs.isDirectory()) {
            String missing = "missing:" + PortablePath.of(abs);
            return missing + "+res:" + missing;
        }
        String identity = ClasspathFingerprint.entry(abs);
        return token(abs, attrs, identity) + "+" + resources(abs, identity);
    }

    /** Memo namespace of {@link #resources}: its own shape, apart from the ABI's. */
    private static final String RESOURCES_NAMESPACE = "meta-inf.1:";

    /**
     * The entry's {@code META-INF} resources by name and content: {@code res:<sha256>}, or {@code
     * res:none} when it has none. An annotation processor reads them through its {@code Filer} or
     * the class loader, and groovyc reads its extension modules and global AST transforms from
     * them, so a compile that runs either keys them beside the ABI. Memoized on the entry's content.
     */
    public static String resources(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        String identity = ClasspathFingerprint.entry(abs);
        return resources(abs, identity);
    }

    private static String resources(Path abs, String identity) throws IOException {
        if (identity.startsWith("missing:")) return "res:" + identity;
        String hit = AbiMemo.get(RESOURCES_NAMESPACE + identity);
        if (hit != null) return hit;
        Map<String, String> found = new TreeMap<>();
        if (Files.isDirectory(abs)) {
            Path metaInf = abs.resolve("META-INF");
            PathUtil.forEachRegularFile(metaInf, (f, a) -> {
                String rel = abs.relativize(f).toString().replace('\\', '/');
                if (!rel.endsWith(".class")
                        && !BuildStamps.isStampFile(f.getFileName().toString())) {
                    found.put(rel, FileHashMemo.contentHash(f.toAbsolutePath().normalize(), a));
                }
            });
        } else {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(abs))) {
                for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    String name = e.getName();
                    if (e.isDirectory() || !name.startsWith("META-INF/") || name.endsWith(".class")) continue;
                    found.put(name, Hashing.sha256Hex(zip.readAllBytes()));
                }
            } catch (ZipException notAZip) {
                // Not an archive: nothing beside its classes.
            }
        }
        Lines digest = new Lines();
        found.forEach((name, sha) -> digest.accept(name + ":" + sha));
        String token = found.isEmpty() ? "res:none" : "res:" + digest.hex();
        AbiMemo.put(RESOURCES_NAMESPACE + identity, token);
        return token;
    }

    /** The service file a jar lists its global Groovy AST transforms in. */
    private static final String GLOBAL_TRANSFORMS = "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";

    /** Whether the entry registers a global Groovy AST transform, which runs on every groovyc compile it is on. */
    public static boolean hasGlobalTransform(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) return Files.isRegularFile(abs.resolve(GLOBAL_TRANSFORMS));
        if (!Files.isRegularFile(abs)) return false;
        String identity = TRANSFORMS_NAMESPACE + ClasspathFingerprint.entry(abs);
        String hit = AbiMemo.get(identity);
        if (hit != null) return Boolean.parseBoolean(hit);
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(abs))) {
            for (ZipEntry e = zip.getNextEntry(); e != null && !found; e = zip.getNextEntry()) {
                found = e.getName().equals(GLOBAL_TRANSFORMS);
            }
        } catch (ZipException notAZip) {
            found = false;
        }
        AbiMemo.put(identity, Boolean.toString(found));
        return found;
    }

    /** Memo namespace of {@link #hasGlobalTransform}, keyed on the jar's content. */
    private static final String TRANSFORMS_NAMESPACE = "groovy-transforms.1:";

    /**
     * The token of a classes tree that is not on disk, from the compile record that produced it:
     * {@code outputs} maps tree-relative paths to the content shas the CAS holds. The forecast
     * uses it for a sibling whose tree a build will restore before the consumer's compile keys
     * on it, so the two agree on the key instead of the forecast reading {@code missing:}.
     *
     * <p>Memoized under the identity the restored tree will have once its resources are copied
     * beside the classes ({@link ClasspathFingerprint#entryFromCompileAndResources}): the build's
     * later sighting of the live tree is then a lookup, as is the next forecast's projection.
     * The token itself reads only the {@code .class} outputs, the same way a live tree is read.
     */
    public static String tokenFromOutputs(Map<String, String> outputs, List<Path> resourceRoots, Cas cas)
            throws IOException {
        return tokenFromOutputs(outputs, resourceRoots, Map.of(), cas);
    }

    /**
     * As above for a tree that also carries {@code copiedFiles} (a module-root plugin manifest
     * {@code copy-resources} places at its root): they shape the identity the token is memoized
     * under, never the token itself, which reads {@code .class} outputs alone.
     */
    public static String tokenFromOutputs(
            Map<String, String> outputs, List<Path> resourceRoots, Map<String, String> copiedFiles, Cas cas)
            throws IOException {
        String identity = ClasspathFingerprint.entryFromCompileAndResources(outputs, resourceRoots, copiedFiles);
        String hit = AbiMemo.get(MEMO_NAMESPACE + identity);
        if (hit != null) return hit;
        EXTRACTS.incrementAndGet();
        // Sorted by tree-relative path, as extractDir sorts a live tree.
        Map<String, String> classes = new TreeMap<>();
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            String slashed = output.getKey().replace('\\', '/');
            if (!slashed.endsWith(".class")) continue;
            if (BuildStamps.isStampFile(slashed)) continue;
            if (ActionCache.hasJkScratchSegment(Path.of(slashed))) continue;
            classes.put(slashed, output.getValue());
        }
        Lines digest = new Lines();
        for (Map.Entry<String, String> cls : classes.entrySet()) {
            digest.accept(cls.getKey());
            for (String line : ClassAbi.apiLines(cas.read(cls.getValue()))) digest.accept(line);
        }
        String abi = "abi:" + digest.hex();
        AbiMemo.put(MEMO_NAMESPACE + identity, abi);
        return abi;
    }

    public static long extracts() {
        return EXTRACTS.get();
    }

    public static void resetStats() {
        EXTRACTS.set(0);
    }

    /** The API lines as a running SHA-256: {@code accept} feeds one line, {@link #hex} closes it. */
    private static final class Lines {
        private final MessageDigest md = Hashing.newSha256();
        private boolean first = true;

        void accept(String line) {
            if (!first) md.update((byte) '\n');
            first = false;
            md.update(line.getBytes(StandardCharsets.UTF_8));
        }

        String hex() {
            return Hashing.hex(md.digest());
        }
    }

    private static void extract(Path abs, BasicFileAttributes attrs, Lines out) throws IOException {
        if (attrs.isDirectory()) {
            extractDir(abs, out);
            return;
        }
        try {
            extractJar(abs, out);
        } catch (ZipException notAZip) {
            out.accept(abs.getFileName().toString());
            for (String line : ClassAbi.apiLines(Files.readAllBytes(abs))) out.accept(line);
        }
    }

    private static void extractDir(Path root, Lines out) throws IOException {
        List<Path> classes = new ArrayList<>();
        // Guard G45: the walk already read each entry's attributes, so ask for them once rather
        // than re-resolving the path to ask again.
        PathUtil.forEachRegularFile(root, (f, a) -> {
            if (BuildStamps.isStampFile(f.getFileName().toString())) return;
            if (ActionCache.hasJkScratchSegment(root.relativize(f))) return;
            if (!f.getFileName().toString().endsWith(".class")) return;
            classes.add(f);
        });
        classes.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
        for (Path f : classes) {
            out.accept(root.relativize(f).toString().replace('\\', '/'));
            for (String line : ClassAbi.apiLines(Files.readAllBytes(f))) out.accept(line);
        }
    }

    /**
     * The classes in name order through {@link JarClassReader}, which holds a window of names at a
     * time rather than the jar's whole directory: a bundle jar of four hundred thousand classes
     * costs the same few megabytes as a small one.
     */
    private static void extractJar(Path jar, Lines out) throws IOException {
        try (JarClassReader classes = JarClassReader.open(jar)) {
            classes.forEachClass((name, bytes) -> {
                out.accept(name);
                for (String line : ClassAbi.apiLines(bytes)) out.accept(line);
            });
        }
    }
}
