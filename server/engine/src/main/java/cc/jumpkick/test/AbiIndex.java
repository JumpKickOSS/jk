// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/** On-disk {@code target/incremental/main-abi.idx}: one {@code FQC\\tapiHex} line (schema). */
public final class AbiIndex {

    public static final String FILE_NAME = "main-abi.idx";

    private AbiIndex() {}

    public static Path path(Path buildDir) {
        return buildDir.resolve("incremental").resolve(FILE_NAME);
    }

    public static Map<String, ClassAbi.Fingerprint> load(Path file) throws IOException {
        Map<String, ClassAbi.Fingerprint> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                String[] p = line.split("\t", -1);
                // Two fields since; an old three-field row is dropped, which just means
                // one full re-scan on the next compile.
                if (p.length == 2) out.put(p[0], new ClassAbi.Fingerprint(p[1]));
            });
        }
        return out;
    }

    /**
     * The previous index advanced by one compile: only the classes of the sources that actually
     * recompiled are re-hashed (their class file gone → row dropped); every other row carries
     * forward. This is what keeps an incremental build from re-reading and re-hashing the whole
     * {@code classes/main} tree per compile. Sources that resolve to no FQC (secondary
     * top-level classes, unknown roots) are ignored; a stale row for them is harmless — ranking
     * re-hashes the class file itself for "current", the index is only ever the "pre" baseline.
     */
    public static Map<String, ClassAbi.Fingerprint> updated(
            Map<String, ClassAbi.Fingerprint> previous, Path moduleDir, List<Path> compiledSources, Path classesDir)
            throws IOException {
        Map<String, ClassAbi.Fingerprint> out = new LinkedHashMap<>(previous);
        Path module = moduleDir.toAbsolutePath().normalize();
        SourceFqcs fqcs = SourceFqcs.of(module, Set.of(TestSuites.DEFAULT));
        for (Path src : compiledSources) {
            if (src == null) continue;
            Path p = src.toAbsolutePath().normalize();
            if (!p.startsWith(module)) continue;
            String rel = module.relativize(p).toString().replace('\\', '/');
            SourceFqcs.Hit hit = fqcs.classify(rel, Set.of(TestSuites.DEFAULT));
            if (hit.kind() != SourceFqcs.Kind.MAIN || hit.fqc() == null) continue;
            Path classFile = classesDir.resolve(hit.fqc().replace('.', '/') + ".class");
            if (Files.isRegularFile(classFile)) {
                out.put(hit.fqc(), fingerprintWithNested(classFile, hit.fqc()));
            } else {
                out.remove(hit.fqc());
            }
        }
        return out;
    }

    /** One owner class file plus its sibling {@code Owner$Named*.class} files. */
    private static ClassAbi.Fingerprint fingerprintWithNested(Path classFile, String fqc) throws IOException {
        String simple = fqc.substring(fqc.lastIndexOf('.') + 1);
        SortedMap<String, byte[]> nested = new TreeMap<>();
        try (Stream<Path> siblings = Files.list(classFile.getParent())) {
            for (Path p : siblings.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith(simple + "$") || !name.endsWith(".class")) continue;
                String stem = name.substring(0, name.length() - ".class".length());
                String suffix = stem.substring(simple.length());
                if (!isNamedNested(suffix)) continue;
                nested.put(stem.substring(simple.length() + 1), Files.readAllBytes(p));
            }
        }
        byte[] owner = Files.readAllBytes(classFile);
        return nested.isEmpty() ? ClassAbi.of(owner) : ClassAbi.of(owner, nested);
    }

    public static void write(Path file, Map<String, ClassAbi.Fingerprint> rows) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (var e : rows.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().apiHex()).append('\n');
        }
        AtomicWrites.replace(file, sb.toString());
    }

    public static Map<String, ClassAbi.Fingerprint> scanClasses(Path classesDir) throws IOException {
        Map<String, ClassAbi.Fingerprint> out = new LinkedHashMap<>();
        if (classesDir == null || !Files.isDirectory(classesDir)) return out;
        Map<String, byte[]> owners = new LinkedHashMap<>();
        Map<String, SortedMap<String, byte[]>> nested = new LinkedHashMap<>();
        PathUtil.forEachRegularFile(classesDir, (p, attrs) -> {
            if (!p.toString().endsWith(".class")) return;
            String rel = classesDir.relativize(p).toString().replace('\\', '/');
            String fqc = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
            int dollar = fqc.indexOf('$');
            if (dollar < 0) {
                owners.put(fqc, Files.readAllBytes(p));
            } else if (isNamedNested(fqc.substring(dollar))) {
                // Named nested classes classify with their owner; anonymous/local
                // ($1, $2$Local…) stay out — a body edit that adds one must stay BODY.
                nested.computeIfAbsent(fqc.substring(0, dollar), k -> new TreeMap<>())
                        .put(fqc.substring(dollar + 1), Files.readAllBytes(p));
            }
        });
        for (var e : owners.entrySet()) {
            SortedMap<String, byte[]> inner = nested.get(e.getKey());
            out.put(e.getKey(), inner == null ? ClassAbi.of(e.getValue()) : ClassAbi.of(e.getValue(), inner));
        }
        return out;
    }

    /** Every {@code $}-segment starts with a non-digit — {@code $Builder} yes, {@code $1}/{@code $2$Local} no. */
    static boolean isNamedNested(String dollarSuffix) {
        for (String seg : dollarSuffix.substring(1).split("\\$", -1)) {
            if (seg.isEmpty() || Character.isDigit(seg.charAt(0))) return false;
        }
        return true;
    }
}
