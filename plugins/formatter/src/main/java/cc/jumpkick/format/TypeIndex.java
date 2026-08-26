// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Known types for FQCN shortening: top-level names from project sources plus (lazily) public JDK
 * classes from {@code jrt:/}. A name shortens when it is in this index and its simple name does
 * not collide.
 */
final class TypeIndex {

    private static volatile TypeIndex jdk;

    private final Set<String> known;
    private final Map<String, List<String>> bySimple;

    private TypeIndex(Set<String> known, Map<String, List<String>> bySimple) {
        this.known = known;
        this.bySimple = bySimple;
    }

    static TypeIndex scan(Collection<Path> sources) {
        return scan(sources, true);
    }

    static TypeIndex scan(Collection<Path> sources, boolean includeJdk) {
        Set<String> known = new LinkedHashSet<>();
        Map<String, List<String>> bySimple = new HashMap<>();
        if (sources != null) {
            for (Path p : sources) addSource(p, known, bySimple);
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

    private static void addSource(Path file, Set<String> known, Map<String, List<String>> bySimple) {
        if (file == null || !Files.isRegularFile(file)) return;
        String name = file.getFileName().toString();
        if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".groovy") && !name.endsWith(".scala")) {
            return;
        }
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            return;
        }
        String pkg = JavaText.packageName(src);
        if (pkg.isEmpty()) return;
        String blanked = JavaText.blankNonCode(src);
        Matcher m = JavaText.TYPE_DECL.matcher(blanked);
        while (m.find()) {
            String simple = m.group(1) != null ? m.group(1) : m.group(2);
            if (simple == null) continue;
            add(pkg + "." + simple, known, bySimple);
        }
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
            if (jdk != null) return jdk;
            jdk = loadJdk();
            return jdk;
        }
    }

    private static TypeIndex loadJdk() {
        Set<String> known = new LinkedHashSet<>();
        Map<String, List<String>> bySimple = new HashMap<>();
        try {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modules = fs.getPath("/modules");
            try (var stream = Files.walk(modules)) {
                stream.filter(p -> {
                            String n = p.getFileName() == null
                                    ? ""
                                    : p.getFileName().toString();
                            return n.endsWith(".class")
                                    && !n.contains("$")
                                    && !n.equals("module-info.class")
                                    && !n.equals("package-info.class");
                        })
                        .forEach(p -> {
                            // /modules/<module>/java/util/List.class
                            int n = p.getNameCount();
                            if (n < 4) return;
                            StringBuilder fq = new StringBuilder();
                            for (int i = 2; i < n; i++) {
                                String seg = p.getName(i).toString();
                                if (i == n - 1) {
                                    if (!seg.endsWith(".class")) return;
                                    seg = seg.substring(0, seg.length() - 6);
                                }
                                if (fq.length() > 0) fq.append('.');
                                fq.append(seg);
                            }
                            add(fq.toString(), known, bySimple);
                        });
            }
        } catch (Exception ignored) {
            // no jrt (unusual) — java.* still shortens via the explicit javaLang / java.* rules
        }
        return new TypeIndex(known, bySimple);
    }
}
