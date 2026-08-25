// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which generated file came from which source, carried across builds in {@code provenance.tsv}.
 *
 * <p>Its own owner because it answers a question Zinc cannot: generated files are not in Zinc's
 * source set, so their class files are unattributed products it never prunes. Deciding that a
 * generated file is now stale needs last run's origin map, and that map is a fact about the workdir
 * with a lifetime longer than one compile — unrelated to the invalidation analysis {@link
 * ZincWorkdir} keeps beside it.
 */
final class GeneratedProvenance {

    private final Path file;

    private GeneratedProvenance(Path workdir) {
        this.file = workdir.resolve("provenance.tsv");
    }

    static GeneratedProvenance of(Path workdir) {
        return new GeneratedProvenance(workdir);
    }

    /**
     * Reconcile annotation-processor outputs against the previous build's provenance. For every
     * generated file recorded last time whose origins were <em>all</em> recompiled this run but which
     * was <em>not</em> regenerated, delete the generated source and its class files (an isolating
     * processor that stopped generating it — e.g. its annotation was removed). Then persist the
     * merged provenance for the next build.
     */
    void reconcile(Path sourceOutput, Path classOutput, List<Path> compiledSources, Map<Path, Set<Path>> newProv)
            throws IOException {
        Map<Path, Set<Path>> prev = read();
        Set<Path> recompiled = new HashSet<>();
        for (Path p : compiledSources) recompiled.add(p.toAbsolutePath().normalize());
        Set<Path> regenerated = normalizeAll(newProv.keySet()); // this run's generated files, normalized

        Map<Path, Set<Path>> merged = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> e : prev.entrySet()) {
            Path gen = e.getKey(); // normalized on write
            Set<Path> origins = e.getValue();
            if (regenerated.contains(gen)) continue; // regenerated this run — newProv is authoritative
            boolean allRecompiled = !origins.isEmpty() && recompiled.containsAll(origins);
            if (allRecompiled) {
                deleteOutputs(gen, sourceOutput, classOutput); // no longer generated → prune
            } else {
                merged.put(gen, origins); // owned by a source that was not recompiled — keep
            }
        }
        for (Map.Entry<Path, Set<Path>> e : newProv.entrySet()) {
            merged.put(e.getKey().toAbsolutePath().normalize(), normalizeAll(e.getValue()));
        }
        write(merged);
    }

    private static void deleteOutputs(Path gen, Path sourceOutput, Path classOutput) throws IOException {
        Files.deleteIfExists(gen); // the generated source/resource itself
        String name = gen.getFileName().toString();
        if (sourceOutput == null || classOutput == null || !name.endsWith(".java")) return;
        Path srcRoot = sourceOutput.toAbsolutePath().normalize();
        Path genAbs = gen.toAbsolutePath().normalize();
        if (!genAbs.startsWith(srcRoot)) return;
        Path rel = srcRoot.relativize(genAbs);
        Path pkgDir = classOutput.resolve(rel).getParent();
        if (pkgDir == null || !Files.isDirectory(pkgDir)) return;
        String stem = name.substring(0, name.length() - ".java".length());
        try (var s = Files.list(pkgDir)) {
            for (Path c : (Iterable<Path>) s::iterator) {
                String cn = c.getFileName().toString();
                // <stem>.class plus nested/anonymous <stem>$Inner.class
                if (cn.equals(stem + ".class") || cn.startsWith(stem + "$")) Files.deleteIfExists(c);
            }
        }
    }

    private static Set<Path> normalizeAll(Set<Path> paths) {
        Set<Path> out = new HashSet<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }

    private Map<Path, Set<Path>> read() throws IOException {
        Map<Path, Set<Path>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            Set<Path> origins = new HashSet<>();
            for (int i = 1; i < parts.length; i++) origins.add(Path.of(parts[i]));
            out.put(Path.of(parts[0]), origins);
        }
        return out;
    }

    private void write(Map<Path, Set<Path>> prov) throws IOException {
        List<String> lines = new ArrayList<>(prov.size());
        for (Map.Entry<Path, Set<Path>> e : prov.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey().toString());
            for (Path origin : e.getValue()) sb.append('\t').append(origin);
            lines.add(sb.toString());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
