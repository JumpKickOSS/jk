// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A facts index for a jar — the "before" side of an API comparison — extracted once and kept
 * beside the guard output keyed by the jar's content hash, so a published release is read once
 * per engine home, not once per build.
 */
public final class JarFacts {

    private JarFacts() {}

    public static FactsIndex of(Path jar, Path cacheDir) throws IOException {
        String sha = Hashing.sha256Hex(jar);
        Path cached = cacheDir.resolve(sha + ".idx");
        if (Files.isRegularFile(cached)) return FactsFormat.read(cached);
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()
                        || !name.endsWith(".class")
                        || name.startsWith("META-INF/")
                        || name.equals("module-info.class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    ClassFacts facts = FactsExtractor.extract(in.readAllBytes());
                    classes.put(facts.name(), facts);
                }
            }
        }
        FactsIndex index = new FactsIndex(classes, Map.of(), sha);
        Files.createDirectories(cacheDir);
        FactsFormat.write(cached, index);
        return index;
    }
}
