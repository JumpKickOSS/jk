// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which generated file came from which source, carried across builds in {@code provenance.tsv}.
 *
 * <p>Its own owner because it answers a question Zinc cannot: generated files are not in Zinc's
 * source set, so their class files are unattributed products it never prunes. Deciding that a
 * generated file is now stale needs last run's origin map, and that map is a fact about the workdir
 * with a lifetime longer than one compile — unrelated to the invalidation analysis {@link
 * ZincWorkdir} keeps beside it.
 *
 * <p>Paths are compared through {@link Canon}, which resolves links in directories only: a source
 * file that is itself a link never reaches this class, because jk's source walks do not follow file
 * links and the generated trees are written by javac and jk's own plugins.
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
     * <p>Every path that takes part in a comparison below goes through one {@link Canon} first.
     * Each question asked here — was this source recompiled, was this file regenerated, is it under
     * my own output root — is "are these two names the same file", and only a link-resolved form
     * answers it.
     */
    void reconcile(
            @Nullable Path sourceOutput, Path classOutput, List<Path> compiledSources, Map<Path, Set<Path>> newProv)
            throws IOException {
        Canon canon = new Canon(sourceOutput == null ? List.of(classOutput) : List.of(sourceOutput, classOutput));
        Path srcRoot = sourceOutput == null ? null : canon.canonical(sourceOutput);
        Path classRoot = canon.canonical(classOutput);
        Map<Path, Set<Path>> prev = read(canon);
        Set<Path> recompiled = canon.all(compiledSources);
        Set<Path> regenerated = canon.all(newProv.keySet()); // this run's generated files

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
            merged.put(canon.canonical(e.getKey()), canon.all(e.getValue()));
        }
        write(merged);
    }

    /**
     * The class files under {@code classOutput} the recorded generated files account for: a class
     * a processor wrote itself, and every class of a generated source under {@code sourceOutput} —
     * {@code Stem.class} and {@code Stem$*.class} in the package directory its path maps to.
     */
    Owned ownedClassFiles(@Nullable Path sourceOutput, Path classOutput) throws IOException {
        Canon canon = new Canon(sourceOutput == null ? List.of(classOutput) : List.of(sourceOutput, classOutput));
        Path srcRoot = sourceOutput == null ? null : canon.canonical(sourceOutput);
        Path classRoot = canon.canonical(classOutput);
        Set<Path> files = new HashSet<>();
        Set<Path> stems = new HashSet<>();
        for (Path gen : read(canon).keySet()) {
            String name = gen.getFileName().toString();
            if (srcRoot != null && name.endsWith(".java") && gen.startsWith(srcRoot)) {
                Path pkgDir = classRoot.resolve(srcRoot.relativize(gen)).getParent();
                if (pkgDir != null) stems.add(pkgDir.resolve(name.substring(0, name.length() - ".java".length())));
            } else if (gen.startsWith(classRoot)) {
                files.add(gen);
            }
        }
        return new Owned(canon, files, stems);
    }

    /** Membership test over the class files under a class output; see {@link #ownedClassFiles}. */
    record Owned(Canon canon, Set<Path> files, Set<Path> stems) {
        boolean owns(Path classFile) {
            Path c = canon.canonical(classFile);
            if (files.contains(c)) return true;
            Path dir = c.getParent();
            String name = c.getFileName().toString();
            if (dir == null || !name.endsWith(".class")) return false;
            int nested = name.indexOf('$');
            String stem = name.substring(0, nested < 0 ? name.length() - ".class".length() : nested);
            return stems.contains(dir.resolve(stem));
        }
    }

    /** All three arguments are already {@link Canon#canonical}, which is what makes the containment test valid. */
    private static void deleteOutputs(Path gen, @Nullable Path srcRoot, @Nullable Path classRoot) throws IOException {
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
     * One reconcile's answer to "which file is this": absolute, {@code ..}-free and with every link in
     * the directory prefix resolved, so that two names for one file compare equal.
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
     * <p>Links live in directories, not in the files this class compares, so the filesystem is asked
     * once per root and once per other directory rather than once per file: a path beneath a root is
     * the root's real path plus the lexical remainder, and any other file is its directory's real
     * path plus its name. {@code toRealPath} costs two orders of magnitude more than the lexical
     * form on Windows, and a compile hands over thousands of files in a few hundred directories.
     *
     * <p>Real-pathing the deepest ancestor that exists and re-appending the rest is what lets this
     * be applied unconditionally, to a path that is not on disk: a {@code sourceOutput} the first
     * build has not created yet, and a generated file in the instant after it is deleted.
     */
    static final class Canon {

        /** Both spellings of each root — as given and as resolved — to the resolved one. */
        private final Map<Path, Path> roots = new LinkedHashMap<>();

        private final Map<Path, Path> dirs = new HashMap<>();
        private int realPathCalls;

        Canon(Collection<Path> roots) {
            for (Path root : roots) {
                Path abs = root.toAbsolutePath().normalize();
                Path real = realDir(abs);
                this.roots.put(abs, real);
                this.roots.put(real, real);
            }
        }

        Path canonical(Path p) {
            Path abs = p.toAbsolutePath().normalize();
            for (Map.Entry<Path, Path> root : roots.entrySet()) {
                if (abs.startsWith(root.getKey()))
                    return root.getValue().resolve(root.getKey().relativize(abs));
            }
            Path parent = abs.getParent();
            Path name = abs.getFileName();
            if (parent == null || name == null) return abs;
            return realDir(parent).resolve(name);
        }

        Set<Path> all(Collection<Path> paths) {
            Set<Path> out = new HashSet<>();
            for (Path p : paths) out.add(canonical(p));
            return out;
        }

        /** How many times the filesystem was asked; one per root and per distinct directory. */
        int realPathCalls() {
            return realPathCalls;
        }

        private Path realDir(Path dir) {
            Path known = dirs.get(dir);
            if (known != null) return known;
            Path real;
            try {
                realPathCalls++;
                real = dir.toRealPath();
            } catch (IOException notThere) {
                Path parent = dir.getParent();
                Path name = dir.getFileName();
                real = parent == null || name == null ? dir : realDir(parent).resolve(name);
            }
            dirs.put(dir, real);
            return real;
        }
    }

    /**
     * Canonicalized on the way in rather than trusted, because the file outlives the build that
     * wrote it: it may carry paths from a jk that did not canonicalize, from before the workspace
     * moved, or from before a link in its prefix was repointed. The alternative — trusting the file
     * because {@link #write} emits canonical paths — makes correctness depend on who wrote it.
     */
    private Map<Path, Set<Path>> read(Canon canon) throws IOException {
        Map<Path, Set<Path>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || isPreEscaping(line)) continue;
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            try {
                Set<Path> origins = new HashSet<>();
                for (int i = 1; i < parts.length; i++) origins.add(canon.canonical(Path.of(unescape(parts[i]))));
                out.put(canon.canonical(Path.of(unescape(parts[0]))), origins);
            } catch (InvalidPathException malformed) {
                // A row this jk cannot read is a row it cannot prune for; the next write drops it.
            }
        }
        return out;
    }

    /**
     * A row written before paths were escaped. {@link #escape} doubles every backslash, so a
     * backslash followed by anything other than {@code \\ t n r} cannot occur in a row this class
     * wrote — it is a raw Windows separator, and unescaping it would turn {@code \target} into a
     * tab. Such a row is skipped rather than decoded into a path that never existed.
     */
    static boolean isPreEscaping(String line) {
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) != '\\') continue;
            char next = line.charAt(++i);
            if (next != '\\' && next != 't' && next != 'n' && next != 'r') return true;
        }
        return false;
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
                default -> out.append(c).append(next); // unreachable for rows this class wrote
            }
        }
        return out.toString();
    }
}
