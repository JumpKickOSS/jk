// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
            for (String line : lines.toList()) {
                if (line.isBlank()) continue;
                String[] p = line.split("\t", 3);
                if (p.length == 3) out.put(p[0], new ClassAbi.Fingerprint(p[1], p[2]));
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
