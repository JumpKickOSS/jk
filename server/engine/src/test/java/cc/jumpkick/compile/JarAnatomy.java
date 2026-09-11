// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Where every byte of a jar goes: per-entry compressed payload, local and central headers, data
 * descriptors and extra fields, read straight from the ZIP structure rather than through
 * {@link java.util.zip.ZipFile} (which hides the local header and the descriptor).
 *
 * <p>{@link Archive#unaccounted()} is zero for every archive the bench reads; a non-zero value means
 * a structure this parser does not model (ZIP64, archive comment) and the report would be wrong.
 */
final class JarAnatomy {

    private JarAnatomy() {}

    /** One entry's on-disk footprint. Sizes are what the central directory records. */
    record Entry(
            String name,
            int method,
            long compressedSize,
            long size,
            long crc,
            boolean dataDescriptor,
            int localExtra,
            int centralExtra,
            int commentLength) {

        boolean directory() {
            return name.endsWith("/");
        }

        boolean stored() {
            return method == 0 && !directory();
        }

        boolean deflated() {
            return method == 8 && !directory();
        }

        boolean sameContent(Entry other) {
            return crc == other.crc && size == other.size;
        }

        /** Local header + central header + data descriptor, without the extra fields. */
        long headers() {
            int n = name.getBytes(StandardCharsets.UTF_8).length;
            return 30 + n + 46 + n + commentLength + (dataDescriptor ? 16 : 0);
        }

        long extras() {
            return localExtra + centralExtra;
        }

        /** Everything this entry costs the archive. */
        long cost() {
            return compressedSize + headers() + extras();
        }
    }

    /** A parsed archive; {@code entries} is ordered by name. */
    record Archive(Path file, long bytes, long eocdBytes, Map<String, Entry> entries) {

        long count(Predicate<Entry> p) {
            return entries.values().stream().filter(p).count();
        }

        long sum(Predicate<Entry> p, ToLongFunction<Entry> f) {
            return entries.values().stream().filter(p).mapToLong(f).sum();
        }

        long files() {
            return count(e -> !e.directory());
        }

        long dirs() {
            return count(Entry::directory);
        }

        long classes() {
            return count(e -> e.name().endsWith(".class"));
        }

        long payload() {
            return sum(e -> true, Entry::compressedSize);
        }

        long extraBytes() {
            return sum(e -> true, Entry::extras);
        }

        long stored() {
            return count(Entry::stored);
        }

        long deflated() {
            return count(Entry::deflated);
        }

        /** Bytes the parser could not place; zero unless the archive has a shape it does not model. */
        long unaccounted() {
            return bytes - sum(e -> true, Entry::cost) - eocdBytes;
        }

        /** Total footprint (payload + headers + extras) of the entries {@code p} selects. */
        long footprint(Predicate<Entry> p) {
            return sum(p, Entry::cost);
        }
    }

    static Archive read(Path jar) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(jar.toFile(), "r")) {
            long length = raf.length();
            long tailStart = Math.max(0, length - 22 - 65_535);
            byte[] tail = new byte[(int) (length - tailStart)];
            raf.seek(tailStart);
            raf.readFully(tail);
            ByteBuffer tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tb.getInt(i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) throw new IOException("no end-of-central-directory record in " + jar);
            int total = tb.getShort(eocd + 10) & 0xffff;
            long cdSize = tb.getInt(eocd + 12) & 0xffffffffL;
            long cdOffset = tb.getInt(eocd + 16) & 0xffffffffL;
            int comment = tb.getShort(eocd + 20) & 0xffff;
            if (total == 0xffff || cdOffset == 0xffffffffL) {
                throw new IOException("ZIP64 archive; this parser reads the classic layout only: " + jar);
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);
            ByteBuffer cb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
            Map<String, Entry> entries = new TreeMap<>();
            byte[] local = new byte[30];
            int p = 0;
            while (p < cd.length) {
                if (cb.getInt(p) != 0x02014b50) throw new IOException("bad central header at " + p + " in " + jar);
                int flags = cb.getShort(p + 8) & 0xffff;
                int method = cb.getShort(p + 10) & 0xffff;
                long crc = cb.getInt(p + 16) & 0xffffffffL;
                long csize = cb.getInt(p + 20) & 0xffffffffL;
                long size = cb.getInt(p + 24) & 0xffffffffL;
                int nameLen = cb.getShort(p + 28) & 0xffff;
                int extraLen = cb.getShort(p + 30) & 0xffff;
                int commentLen = cb.getShort(p + 32) & 0xffff;
                long localOffset = cb.getInt(p + 42) & 0xffffffffL;
                String name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
                raf.seek(localOffset);
                raf.readFully(local);
                ByteBuffer lb = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN);
                if (lb.getInt(0) != 0x04034b50) throw new IOException("bad local header for " + name + " in " + jar);
                int localExtra = lb.getShort(28) & 0xffff;
                entries.put(
                        name,
                        new Entry(name, method, csize, size, crc, (flags & 8) != 0, localExtra, extraLen, commentLen));
                p += 46 + nameLen + extraLen + commentLen;
            }
            return new Archive(jar, length, 22 + comment, entries);
        }
    }

    /**
     * Every byte of {@code jk.bytes() - other.bytes()}, attributed to a named cause. The buckets sum
     * to the total delta exactly, so a cause that is not listed does not exist.
     *
     * @param projectPrefix the fixture's own package directory ({@code demo/}); its classes differ
     *     between tools because of compiler flags, not packaging
     */
    static Attribution attribute(Archive jk, Archive other, String projectPrefix) {
        Attribution a = new Attribution(jk.bytes() - other.bytes());
        for (Entry e : jk.entries().values()) {
            Entry o = other.entries().get(e.name());
            if (o == null) {
                a.add(onlyInJk(e), e.name(), e.cost());
                continue;
            }
            a.add("extra-fields", e.name(), e.extras() - o.extras());
            a.add("entry-headers", e.name(), e.headers() - o.headers());
            long payload = e.compressedSize() - o.compressedSize();
            if (e.sameContent(o)) {
                a.add("deflate-on-identical-content", e.name(), payload);
            } else {
                a.add(differing(e.name(), projectPrefix), e.name(), payload);
            }
        }
        for (Entry o : other.entries().values()) {
            if (!jk.entries().containsKey(o.name())) a.add(onlyInOther(o), o.name(), -o.cost());
        }
        a.add("end-of-central-directory", "", jk.eocdBytes() - other.eocdBytes());
        return a;
    }

    private static String onlyInJk(Entry e) {
        String n = e.name();
        if (n.contains("META-INF/sbom/")) return "sbom (jk-only content)";
        if (n.startsWith("BOOT-INF/lib/")) return "nested jars (Boot layout)";
        if (n.endsWith(".kotlin_module")) return "kotlin_module naming";
        if (n.endsWith(".class")) return "dependency versions (resolution divergence)";
        if (e.directory()) return "directory entries";
        return "only in jk (other)";
    }

    private static String onlyInOther(Entry e) {
        String n = e.name();
        if (n.startsWith("META-INF/maven/")) return "META-INF/maven pom metadata (tool adds its own)";
        if (n.startsWith("BOOT-INF/lib/")) return "nested jars (Boot layout)";
        if (n.endsWith("module-info.class")) return "module-info.class kept by the tool";
        if (n.endsWith(".kotlin_module")) return "kotlin_module naming";
        if (n.endsWith(".class")) return "dependency versions (resolution divergence)";
        if (e.directory()) return "directory entries";
        return "only in tool (other)";
    }

    private static String differing(String name, String projectPrefix) {
        if (name.equals("META-INF/MANIFEST.MF")) return "manifest (Sbom-* attributes)";
        if (AssemblyPackager.isMergeFile(name)) return "merged META-INF files (separator, order)";
        if (name.startsWith(projectPrefix)) return "project classes (javac debug attributes)";
        if (name.endsWith(".class")) return "dependency versions (resolution divergence)";
        if (name.startsWith("META-INF/maven/")) return "META-INF/maven pom metadata (version or first-wins)";
        return "first-wins picks (LICENSE, NOTICE, versions.properties, …)";
    }

    /** Ordered bucket → byte delta, with the entries behind each bucket. */
    static final class Attribution {
        final long total;
        private final Map<String, Long> bytes = new LinkedHashMap<>();
        private final Map<String, List<String>> names = new LinkedHashMap<>();

        Attribution(long total) {
            this.total = total;
        }

        void add(String bucket, String name, long delta) {
            if (delta == 0) return;
            bytes.merge(bucket, delta, Long::sum);
            names.computeIfAbsent(bucket, k -> new ArrayList<>()).add(name);
        }

        Map<String, Long> buckets() {
            return bytes;
        }

        List<String> names(String bucket) {
            return names.getOrDefault(bucket, List.of());
        }

        long sum() {
            return bytes.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** {@code META-INF/maven/**}: build metadata every tool copies out of every dependency. */
    static boolean isMavenMetadata(Entry e) {
        return e.name().startsWith("META-INF/maven/");
    }

    /** Licence and notice files, at the root of {@code META-INF} or in a {@code licenses/} folder. */
    static boolean isLicenceFile(Entry e) {
        String n = e.name();
        if (!n.startsWith("META-INF/") || e.directory()) return false;
        String upper = n.substring("META-INF/".length()).toUpperCase(Locale.ROOT);
        return upper.startsWith("LICENSE")
                || upper.startsWith("NOTICE")
                || upper.startsWith("LICENSES/")
                || upper.startsWith("LGPL")
                || upper.startsWith("COPYRIGHT")
                || upper.equals("DEPENDENCIES");
    }
}
