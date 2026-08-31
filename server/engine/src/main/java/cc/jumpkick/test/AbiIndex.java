// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** On-disk {@code target/incremental/main-abi.idx}: one {@code FQC\\tapiHex\\tbodyHex} line. */
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
                String[] p = line.split("\t", 3);
                if (p.length == 3) out.put(p[0], new ClassAbi.Fingerprint(p[1], p[2]));
            });
        }
        return out;
    }

    /**
     * The previous index advanced by one compile: only the classes of the sources that actually
     * recompiled are re-hashed (their class file gone → row dropped); every other row carries
     * forward. This is what keeps an incremental build from re-reading and re-hashing the whole
     * {@code classes/main} tree per compile (JK-2610). Sources that resolve to no FQC (secondary
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
                out.put(hit.fqc(), ClassAbi.of(Files.readAllBytes(classFile)));
            } else {
                out.remove(hit.fqc());
            }
        }
        return out;
    }

    public static void write(Path file, Map<String, ClassAbi.Fingerprint> rows) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (var e : rows.entrySet()) {
            sb.append(e.getKey())
                    .append('\t')
                    .append(e.getValue().apiHex())
                    .append('\t')
                    .append(e.getValue().bodyHex())
                    .append('\n');
        }
        AtomicWrites.replace(file, sb.toString());
    }

    public static Map<String, ClassAbi.Fingerprint> scanClasses(Path classesDir) throws IOException {
        Map<String, ClassAbi.Fingerprint> out = new LinkedHashMap<>();
        if (classesDir == null || !Files.isDirectory(classesDir)) return out;
        PathUtil.forEachRegularFile(classesDir, (p, attrs) -> {
            if (!p.toString().endsWith(".class")) return;
            String rel = classesDir.relativize(p).toString().replace('\\', '/');
            if (rel.contains("$")) return;
            String fqc = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
            out.put(fqc, ClassAbi.of(Files.readAllBytes(p)));
        });
        return out;
    }
}
