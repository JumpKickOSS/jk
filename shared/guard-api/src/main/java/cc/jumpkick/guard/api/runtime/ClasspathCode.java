// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * The code a class path gives the JVM, for {@link ReadLedger#stamp}: every file of every entry but
 * the compile's stamps, which carry a clock, and the classes the JVM never loads from that entry.
 *
 * <p>A class an earlier entry already defines is never loaded from a later one. A jar that defines
 * no such class counts by its bytes. One that does, as a runner vendoring its dependencies does,
 * counts by its resources and the classes it still defines that its {@code Main-Class} and service
 * providers reach through their constant pools; with neither, by every class it still defines. A
 * jar is named by its contents alone, so the same jar at another content-addressed path is the same
 * code.
 */
final class ClasspathCode {

    private static final String CLASS = ".class";
    private static final String SERVICES = "META-INF/services/";

    /**
     * A dotted or slashed binary class name inside a constant-pool string; in a descriptor it carries
     * the {@code L} ahead of it.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:[./][A-Za-z_$][\\w$]*)+");

    private ClasspathCode() {}

    /** Feed the code of {@code classpath}, in order, to {@code md}. */
    static void feed(MessageDigest md, List<Path> classpath) throws IOException {
        Set<String> defined = new HashSet<>();
        for (Path entry : classpath) {
            Path abs = entry.toAbsolutePath().normalize();
            if (Files.isDirectory(abs)) {
                ReadLedger.feed(md, "entry", abs.toString());
                TreeMap<String, Path> files = new TreeMap<>();
                PathUtil.forEachRegularFile(abs, d -> false, (f, attrs) -> {
                    String rel = abs.relativize(f).toString().replace('\\', '/');
                    if (!BuildStamps.isStampFile(rel)) files.put(rel, f);
                });
                for (var e : files.entrySet()) {
                    if (defines(defined, e.getKey())) ReadLedger.feed(md, e.getKey(), Hashing.sha256Hex(e.getValue()));
                }
            } else if (Files.isRegularFile(abs)) {
                feedJar(md, abs, defined);
            } else {
                ReadLedger.feed(md, "absent", abs.toString());
            }
        }
    }

    /** Whether the JVM may load {@code name} from this entry; a class is recorded as defined by it. */
    private static boolean defines(Set<String> defined, String name) {
        return !name.endsWith(CLASS) || defined.add(name);
    }

    private static void feedJar(MessageDigest md, Path jar, Set<String> defined) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            TreeMap<String, ZipEntry> entries = new TreeMap<>();
            for (var it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                if (!e.isDirectory()) entries.put(e.getName(), e);
            }
            boolean shadows = entries.keySet().stream().anyMatch(n -> n.endsWith(CLASS) && defined.contains(n));
            if (!shadows) {
                entries.keySet().forEach(n -> defines(defined, n));
                ReadLedger.feed(md, "jar", Hashing.sha256Hex(jar));
                return;
            }
            Map<String, ZipEntry> own = new HashMap<>();
            entries.forEach((n, e) -> {
                if (n.endsWith(CLASS) && defines(defined, n)) own.put(n, e);
            });
            Set<String> reached = reached(zip, entries, own);
            ReadLedger.feed(md, "jar", "");
            for (var e : entries.entrySet()) {
                String n = e.getKey();
                if (n.endsWith(CLASS) && !reached.contains(n)) continue;
                try (InputStream in = zip.getInputStream(e.getValue())) {
                    ReadLedger.feed(md, n, Hashing.sha256Hex(in.readAllBytes()));
                }
            }
        } catch (ZipException notAZip) {
            ReadLedger.feed(md, "bytes", Hashing.sha256Hex(jar));
        }
    }

    /**
     * The entries of {@code own} that the jar's {@code Main-Class} and service providers reach, or all
     * of them when it names neither or a class on the way does not read.
     */
    private static Set<String> reached(ZipFile zip, Map<String, ZipEntry> entries, Map<String, ZipEntry> own)
            throws IOException {
        Deque<String> work = new ArrayDeque<>(roots(zip, entries));
        if (work.isEmpty()) return own.keySet();
        Set<String> reached = new HashSet<>();
        while (!work.isEmpty()) {
            String name = work.poll();
            ZipEntry e = own.get(name);
            if (e == null || !reached.add(name)) continue;
            List<String> strings;
            try (InputStream in = zip.getInputStream(e)) {
                strings = poolStrings(in.readAllBytes());
            } catch (IOException unreadable) {
                return own.keySet();
            }
            for (String s : strings) {
                Matcher m = NAME.matcher(s);
                while (m.find()) {
                    String found = m.group().replace('.', '/');
                    work.add(found + CLASS);
                    if (found.startsWith("L")) work.add(found.substring(1) + CLASS);
                }
            }
        }
        return reached;
    }

    /** The classes the JVM starts from in this jar: its {@code Main-Class} and every service provider. */
    private static List<String> roots(ZipFile zip, Map<String, ZipEntry> entries) throws IOException {
        List<String> roots = new ArrayList<>();
        ZipEntry manifest = entries.get("META-INF/MANIFEST.MF");
        if (manifest != null) {
            try (InputStream in = zip.getInputStream(manifest)) {
                String main = new Manifest(in).getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                if (main != null) roots.add(main.trim().replace('.', '/') + CLASS);
            }
        }
        for (var e : entries.entrySet()) {
            if (!e.getKey().startsWith(SERVICES)) continue;
            try (InputStream in = zip.getInputStream(e.getValue())) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    int hash = line.indexOf('#');
                    String provider = (hash < 0 ? line : line.substring(0, hash)).trim();
                    if (!provider.isEmpty()) roots.add(provider.replace('.', '/') + CLASS);
                }
            }
        }
        return roots;
    }

    /** Every UTF-8 string of a class file's constant pool: the names, descriptors and literals it spells. */
    private static List<String> poolStrings(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.skipBytes(4);
        int count = in.readUnsignedShort();
        List<String> strings = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> strings.add(in.readUTF());
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    i++;
                }
                default -> throw new IOException("constant pool tag " + tag);
            }
        }
        return strings;
    }
}
