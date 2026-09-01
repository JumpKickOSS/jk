// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Whole-entry JVM ABI token for a jar or classes directory. {@code abi:<sha256>} of the sorted
 * per-class API lines; {@code missing:<abs>} when the path is absent. Memoized on {@link
 * ClasspathFingerprint#entry} so the second sighting of the same bytes is a lookup. Does not switch
 * compile action keys.
 */
public final class ClasspathAbi {

    private static final AtomicLong EXTRACTS = new AtomicLong();

    private ClasspathAbi() {}

    public static String token(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            return "missing:" + abs;
        }
        if (!attrs.isRegularFile() && !attrs.isDirectory()) {
            return "missing:" + abs;
        }
        String identity = ClasspathFingerprint.entry(abs);
        if (identity.startsWith("missing:")) return identity;
        String hit = AbiMemo.get(identity);
        if (hit != null) return hit;
        EXTRACTS.incrementAndGet();
        String abi = "abi:" + Hashing.sha256Hex(String.join("\n", extractLines(abs, attrs)));
        AbiMemo.put(identity, abi);
        return abi;
    }

    public static long extracts() {
        return EXTRACTS.get();
    }

    public static void resetStats() {
        EXTRACTS.set(0);
    }

    private static List<String> extractLines(Path abs, BasicFileAttributes attrs) throws IOException {
        if (attrs.isDirectory()) return extractDir(abs);
        try {
            return extractJar(abs);
        } catch (ZipException notAZip) {
            List<String> lines = new ArrayList<>();
            lines.add(abs.getFileName().toString());
            lines.addAll(ClassAbi.apiLines(Files.readAllBytes(abs)));
            return lines;
        }
    }

    private static List<String> extractDir(Path root) throws IOException {
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
        List<String> lines = new ArrayList<>();
        for (Path f : classes) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            lines.add(rel);
            lines.addAll(ClassAbi.apiLines(Files.readAllBytes(f)));
        }
        return lines;
    }

    private static List<String> extractJar(Path jar) throws IOException {
        List<String> lines = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> classes = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry e : classes) {
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                lines.add(e.getName());
                lines.addAll(ClassAbi.apiLines(bytes));
            }
        }
        return lines;
    }
}
