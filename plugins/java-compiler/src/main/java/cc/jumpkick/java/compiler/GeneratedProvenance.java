// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
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
     *
     * <p>Every path that takes part in a comparison below goes through {@link #canonical} first.
     * Each question asked here — was this source recompiled, was this file regenerated, is it under
     * my own output root — is "are these two names the same file", and only a link-resolved form
     * answers it.
     */
    void reconcile(Path sourceOutput, Path classOutput, List<Path> compiledSources, Map<Path, Set<Path>> newProv)
            throws IOException {
        Path srcRoot = canonical(sourceOutput);
        Path classRoot = canonical(classOutput);
        Map<Path, Set<Path>> prev = read();
        Set<Path> recompiled = canonicalAll(compiledSources);
        Set<Path> regenerated = canonicalAll(newProv.keySet()); // this run's generated files

        Map<Path, Set<Path>> merged = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> e : prev.entrySet()) {
            Path gen = e.getKey(); // canonical on read
            Set<Path> origins = e.getValue();
            if (regenerated.contains(gen)) continue; // regenerated this run — newProv is authoritative
            boolean allRecompiled = !origins.isEmpty() && recompiled.containsAll(origins);
            if (allRecompiled) {
                deleteOutputs(gen, srcRoot, classRoot); // no longer generated → prune
            } else {
                merged.put(gen, origins); // owned by a source that was not recompiled — keep
            }
        }
        for (Map.Entry<Path, Set<Path>> e : newProv.entrySet()) {
            merged.put(canonical(e.getKey()), canonicalAll(e.getValue()));
        }
        write(merged);
    }

    /** All three arguments are already {@link #canonical}, which is what makes the containment test valid. */
    private static void deleteOutputs(Path gen, Path srcRoot, Path classRoot) throws IOException {
        Files.deleteIfExists(gen); // the generated source/resource itself
        String name = gen.getFileName().toString();
        if (srcRoot == null || classRoot == null || !name.endsWith(".java")) return;
        if (!gen.startsWith(srcRoot)) return; // a resource written somewhere else; not ours to map
        Path rel = srcRoot.relativize(gen);
        Path pkgDir = classRoot.resolve(rel).getParent();
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

    /**
     * Absolute and symlink-resolved, so that two names for one file compare equal.
     *
     * <p>The paths meeting here come from three sources that disagree about links, and none of them
     * says so: javac real-paths the URIs it returns from {@code Filer} and {@code Trees}, Zinc's
     * converter reports the path it was handed, and an output root arrives as the build wrote it.
     * {@code toAbsolutePath().normalize()} resolves {@code ..} and nothing else, so one link
     * anywhere in the prefix is enough to make a true containment test read false —
     * {@code /private/var/…/gen-src/app/WidgetGen.java} is not {@code startsWith}
     * {@code /var/…/gen-src}, and the stale class file survives the prune this class exists to
     * perform. macOS reaches that state with no help, because {@code $TMPDIR} lives under the
     * {@code /var} → {@code /private/var} link; a symlinked workspace, worktree or {@code $HOME}
     * reaches it anywhere.
     *
     * <p>Real-pathing the deepest ancestor that exists and re-appending the rest is what lets this
     * be applied unconditionally, to a path that is not on disk: a {@code sourceOutput} the first
     * build has not created yet, and a generated file in the instant after it is deleted.
     */
    private static Path canonical(Path p) {
        if (p == null) return null;
        Path abs = p.toAbsolutePath().normalize();
        Deque<Path> tail = new ArrayDeque<>();
        for (Path probe = abs; probe != null; probe = probe.getParent()) {
            try {
                Path real = probe.toRealPath();
                for (Path name : tail) real = real.resolve(name);
                return real;
            } catch (IOException notThere) {
                Path name = probe.getFileName();
                if (name == null) break; // the root itself will not resolve; nothing left to walk up to
                tail.addFirst(name);
            }
        }
        return abs;
    }

    private static Set<Path> canonicalAll(Collection<Path> paths) {
        Set<Path> out = new HashSet<>();
        for (Path p : paths) out.add(canonical(p));
        return out;
    }

    /**
     * Canonicalized on the way in rather than trusted, because the file outlives the build that
     * wrote it: it may carry paths from a jk that did not canonicalize, from before the workspace
     * moved, or from before a link in its prefix was repointed. The alternative — trusting the file
     * because {@link #write} emits canonical paths — makes correctness depend on who wrote it.
     */
    private Map<Path, Set<Path>> read() throws IOException {
        Map<Path, Set<Path>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            Set<Path> origins = new HashSet<>();
            for (int i = 1; i < parts.length; i++) origins.add(canonical(Path.of(unescape(parts[i]))));
            out.put(canonical(Path.of(unescape(parts[0]))), origins);
        }
        return out;
    }

    private void write(Map<Path, Set<Path>> prov) throws IOException {
        List<String> lines = new ArrayList<>(prov.size());
        for (Map.Entry<Path, Set<Path>> e : prov.entrySet()) {
            StringBuilder sb = new StringBuilder(escape(e.getKey().toString()));
            for (Path origin : e.getValue()) sb.append('\t').append(escape(origin.toString()));
            lines.add(sb.toString());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * TSV needs its delimiters out of the data: a tab or newline is legal in a POSIX path, and
     * unescaped it split the record into bogus columns (or across lines) — the row read as
     * malformed, was silently skipped, and the prune this class exists to perform stopped for
     * that file.
     */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> out.append(c).append(next); // pre-escaping rows pass through untouched
            }
        }
        return out.toString();
    }
}
