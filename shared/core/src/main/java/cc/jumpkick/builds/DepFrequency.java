// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Host-wide declared-dependency frequency under {@code builds/projects/dep-frequency.toml}.
 * One project id maps to the set of declared dep keys last observed for that checkout; {@link
 * #top} ranks by how many projects list each key.
 */
public final class DepFrequency {

    public static final String FILE_NAME = "dep-frequency.toml";
    public static final int SCHEMA = 1;

    private static final Pattern PROJECTS_HEADER = Pattern.compile("^\\[projects\\.\"((?:[^\"\\\\]|\\\\.)*)\"\\]\\s*$");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Map<String, NavigableSet<String>> byProject;

    private DepFrequency(Map<String, ? extends Set<String>> byProject) {
        Map<String, NavigableSet<String>> copy = new TreeMap<>();
        for (var e : byProject.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            copy.put(e.getKey(), Collections.unmodifiableNavigableSet(new TreeSet<>(e.getValue())));
        }
        this.byProject = Collections.unmodifiableNavigableMap(new TreeMap<>(copy));
    }

    public static Path file() {
        return file(ProjectBuilds.buildsRoot());
    }

    public static Path file(Path buildsRoot) {
        return ProjectBuilds.projectsRoot(buildsRoot).resolve(FILE_NAME);
    }

    public static DepFrequency load() {
        return load(ProjectBuilds.buildsRoot());
    }

    /** Missing or unreadable file → empty frequency map. */
    public static DepFrequency load(Path buildsRoot) {
        Path f = file(buildsRoot);
        if (!Files.isRegularFile(f)) return empty();
        try {
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return empty();
        }
    }

    public static DepFrequency empty() {
        return new DepFrequency(Map.of());
    }

    /** Immutable view of projectId → declared dep keys (sorted). */
    public Map<String, NavigableSet<String>> projects() {
        return byProject;
    }

    /**
     * Replace {@code projectId}'s declared deps with a TreeSet-normalized copy of {@code deps}.
     * Other projects are unchanged.
     */
    public DepFrequency observe(String projectId, Set<String> deps) {
        Objects.requireNonNull(projectId, "projectId");
        if (projectId.isBlank()) throw new IllegalArgumentException("projectId must not be blank");
        Map<String, Set<String>> next = new LinkedHashMap<>();
        for (var e : byProject.entrySet()) next.put(e.getKey(), e.getValue());
        TreeSet<String> normalized = new TreeSet<>();
        if (deps != null) {
            for (String d : deps) {
                if (d != null && !d.isBlank()) normalized.add(d);
            }
        }
        next.put(projectId, normalized);
        return new DepFrequency(next);
    }

    public void save() throws IOException {
        save(ProjectBuilds.buildsRoot());
    }

    public void save(Path buildsRoot) throws IOException {
        AtomicWrites.replace(file(buildsRoot), render());
    }

    /**
     * Aggregate count of projects listing each dep; sort by count descending then name ascending;
     * skip {@code exclude}; return at most {@code limit} names.
     */
    public List<String> top(int limit, Set<String> exclude) {
        if (limit <= 0) return List.of();
        Set<String> skip = exclude == null ? Set.of() : exclude;
        Map<String, Integer> counts = new HashMap<>();
        for (Set<String> deps : byProject.values()) {
            for (String d : deps) {
                if (skip.contains(d)) continue;
                counts.merge(d, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>(Math.min(limit, ranked.size()));
        for (var e : ranked) {
            out.add(e.getKey());
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("schema = ").append(SCHEMA).append('\n');
        for (var e : byProject.entrySet()) {
            sb.append('\n');
            sb.append("[projects.").append(MinimalToml.quote(e.getKey())).append("]\n");
            sb.append("deps = [");
            List<String> deps = new ArrayList<>(e.getValue());
            for (int i = 0; i < deps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(MinimalToml.quote(deps.get(i)));
            }
            sb.append("]\n");
        }
        return sb.toString();
    }

    static DepFrequency parse(String toml) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        String currentId = null;
        boolean inDepsArray = false;
        Set<String> currentDeps = null;
        for (String raw : toml.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (inDepsArray) {
                collectDepsLine(line, currentDeps);
                if (line.contains("]")) {
                    inDepsArray = false;
                    if (currentId != null) map.put(currentId, new TreeSet<>(currentDeps));
                    currentDeps = null;
                }
                continue;
            }
            Matcher header = PROJECTS_HEADER.matcher(line);
            if (header.matches()) {
                currentId = unescape(header.group(1));
                currentDeps = new HashSet<>();
                continue;
            }
            if (line.startsWith("[")) {
                currentId = null;
                currentDeps = null;
                continue;
            }
            if (currentId == null) continue;
            if (line.startsWith("deps")) {
                int open = line.indexOf('[');
                if (open < 0) continue;
                currentDeps = new HashSet<>();
                String rest = line.substring(open + 1);
                int close = rest.lastIndexOf(']');
                if (close >= 0) {
                    collectDepsLine(rest.substring(0, close), currentDeps);
                    map.put(currentId, new TreeSet<>(currentDeps));
                    currentDeps = null;
                } else {
                    collectDepsLine(rest, currentDeps);
                    inDepsArray = true;
                }
            }
        }
        return new DepFrequency(map);
    }

    private static void collectDepsLine(String line, @Nullable Set<String> deps) {
        if (deps == null) return;
        Matcher m = QUOTED.matcher(line);
        while (m.find()) {
            String v = unescape(m.group(1));
            if (!v.isBlank()) deps.add(v);
        }
    }

    /** {@link MinimalToml#unquote} over a regex capture that carries the quote body without its quotes. */
    private static String unescape(String s) {
        return MinimalToml.unquote('"' + s + '"');
    }
}
