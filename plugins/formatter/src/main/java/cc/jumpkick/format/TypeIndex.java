// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * Known types for FQCN shortening: top-level names from project sources plus (lazily) public JDK
 * classes from {@code jrt:/}. A name shortens when it is in this index and its simple name does
 * not collide. A served worker keeps each source's names between runs and re-reads only the sources
 * that changed.
 */
final class TypeIndex {

    private static volatile @Nullable TypeIndex jdk;

    private final Set<String> known;
    private final Map<String, List<String>> bySimple;

    private TypeIndex(Set<String> known, Map<String, List<String>> bySimple) {
        this.known = known;
        this.bySimple = bySimple;
    }

    static TypeIndex scan(Collection<Path> sources) {
        return scan(sources, true);
    }

    /**
     * {@link #scan(Collection)} on the first {@code get}, then that one index for every caller:
     * a run that never needs the index never reads the workspace or the JDK.
     */
    static Supplier<TypeIndex> lazy(Collection<Path> sources) {
        return new Supplier<>() {
            private @Nullable TypeIndex built;

            @Override
            public synchronized TypeIndex get() {
                TypeIndex index = built;
                if (index == null) {
                    index = scan(sources);
                    built = index;
                }
                return index;
            }
        };
    }

    /**
     * Sources are read and lexed in parallel, then indexed in their given order, so the index is
     * the same whatever the thread timing.
     */
    static TypeIndex scan(Collection<Path> sources, boolean includeJdk) {
        Set<String> known = new LinkedHashSet<>();
        Map<String, List<String>> bySimple = new HashMap<>();
        if (sources != null) {
            // This runs in the formatter's own worker process, where no request value is carried.
            List<List<String>> declared = new ArrayList<>(sources)
                    .parallelStream().map(TypeIndex::declaredIn).toList();
            if (DECLARED.size() > MAX_REMEMBERED) forgetAllBut(sources);
            for (List<String> types : declared) {
                for (String fqcn : types) add(fqcn, known, bySimple);
            }
        }
        TypeIndex project = new TypeIndex(known, bySimple);
        return includeJdk ? project.merge(jdk()) : project;
    }

    /** Empty project index plus JDK types. */
    static TypeIndex jdkOnly() {
        return jdk();
    }

    boolean contains(String fqcn) {
        return known.contains(fqcn);
    }

    /** Every indexed FQCN with this simple name (empty if none). */
    List<String> fqcnsNamed(String simple) {
        List<String> hit = bySimple.get(simple);
        return hit == null ? List.of() : hit;
    }

    private TypeIndex merge(TypeIndex other) {
        if (other == null || other.known.isEmpty()) return this;
        Set<String> k = new LinkedHashSet<>(known);
        k.addAll(other.known);
        Map<String, List<String>> b = new HashMap<>(bySimple);
        for (var e : other.bySimple.entrySet()) {
            b.computeIfAbsent(e.getKey(), x -> new ArrayList<>()).addAll(e.getValue());
        }
        return new TypeIndex(k, b);
    }

    /**
     * Each source's top-level types as last read, by absolute path, so a served worker's next scan
     * reads and lexes only the files whose size or modification time moved.
     */
    private static final Map<Path, Declared> DECLARED = new ConcurrentHashMap<>();

    /** Sources remembered before a scan forgets every one it was not given. */
    static final int MAX_REMEMBERED = 65_536;

    /** The types one source declared when it had this size and modification time. */
    private record Declared(long size, FileTime modified, List<String> types) {}

    /** {@link #topLevelTypes} of {@code file}, from {@link #DECLARED} while its stamp holds. */
    private static List<String> declaredIn(@Nullable Path file) {
        if (file == null) return List.of();
        Path key = file.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(key, BasicFileAttributes.class);
        } catch (IOException gone) {
            DECLARED.remove(key);
            return List.of();
        }
        Declared known = DECLARED.get(key);
        if (known != null && known.size() == attrs.size() && known.modified().equals(attrs.lastModifiedTime())) {
            return known.types();
        }
        List<String> types = topLevelTypes(key);
        DECLARED.put(key, new Declared(attrs.size(), attrs.lastModifiedTime(), types));
        return types;
    }

    /** Drop every remembered source but {@code sources}. */
    private static void forgetAllBut(Collection<Path> sources) {
        Set<Path> keep = new HashSet<>();
        for (Path p : sources) if (p != null) keep.add(p.toAbsolutePath().normalize());
        DECLARED.keySet().retainAll(keep);
    }

    /** The top-level types {@code file} declares, as FQCNs in declaration order. */
    private static List<String> topLevelTypes(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        String name = file.getFileName().toString();
        if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".groovy") && !name.endsWith(".scala")) {
            return List.of();
        }
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            return List.of();
        }
        String blanked = JavaText.blanked(src);
        String pkg = JavaText.packageName(blanked);
        if (pkg.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = JavaText.TYPE_DECL.matcher(blanked);
        // Only a declaration at brace depth 0 is a top-level type, and only a top-level type is
        // named {@code package.Simple}. Indexing every match invented an FQCN for each nested,
        // inner, local and member enum/interface declaration — on this tree's shared/core alone,
        // 112 phantom names against 135 real ones. A phantom is not just dead weight: it fabricates
        // a collision that suppresses a legitimate shortening, and it is enough on its own to emit
        // an import of a type that does not exist under the name imported.
        int cursor = 0;
        int depth = 0;
        while (m.find()) {
            depth += braceDelta(blanked, cursor, m.start());
            cursor = m.start();
            if (depth != 0) continue;
            String simple = m.group(1) != null ? m.group(1) : m.group(2);
            if (simple == null) continue;
            out.add(pkg + "." + simple);
        }
        return out;
    }

    /** Net {@code { }} nesting change over {@code [from, to)} of an already-blanked source. */
    private static int braceDelta(String blanked, int from, int to) {
        int delta = 0;
        for (int i = from; i < to; i++) {
            char c = blanked.charAt(i);
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    private static void add(String fqcn, Set<String> known, Map<String, List<String>> bySimple) {
        if (!known.add(fqcn)) return;
        bySimple.computeIfAbsent(JavaText.simpleName(fqcn), k -> new ArrayList<>())
                .add(fqcn);
    }

    static TypeIndex jdk() {
        TypeIndex cached = jdk;
        if (cached != null) return cached;
        synchronized (TypeIndex.class) {
            TypeIndex again = jdk;
            if (again != null) return again;
            TypeIndex loaded = loadJdk();
            jdk = loaded;
            return loaded;
        }
    }

    private static TypeIndex loadJdk() {
        Set<String> known = new LinkedHashSet<>();
        Map<String, List<String>> bySimple = new HashMap<>();
        try {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modules = fs.getPath("/modules");
            PathUtil.forEachRegularFile(modules, (p, attrs) -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(".class")
                        || name.contains("$")
                        || name.equals("module-info.class")
                        || name.equals("package-info.class")) {
                    return;
                }
                // /modules/<module>/java/util/List.class
                int n = p.getNameCount();
                if (n < 4) return;
                StringBuilder fq = new StringBuilder();
                for (int i = 2; i < n; i++) {
                    String seg = p.getName(i).toString();
                    if (i == n - 1) seg = seg.substring(0, seg.length() - 6);
                    if (fq.length() > 0) fq.append('.');
                    fq.append(seg);
                }
                add(fq.toString(), known, bySimple);
            });
        } catch (Exception ignored) {
            // no jrt (unusual) — java.* still shortens via the explicit javaLang / java.* rules
        }
        return new TypeIndex(known, bySimple);
    }
}
