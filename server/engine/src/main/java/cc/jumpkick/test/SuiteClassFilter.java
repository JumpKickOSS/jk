// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which compiled test classes a run's suites own when the module declares class-pattern suites
 * ({@code [test.suites.<name>] classes}). A class from another suite's directory belongs to that
 * suite. A class from the default suite's root belongs to every pattern suite whose patterns match
 * it (nested classes go with their outer class), and to the default suite when none does.
 */
public final class SuiteClassFilter {

    /** The filter of a module without class-pattern suites: every compiled class is the run's. */
    public static final SuiteClassFilter NONE = new SuiteClassFilter(Map.of(), List.of(), List.of());

    private final Map<String, List<String>> patternSuites;
    /** The resolved suites, in selection order. */
    private final List<String> selected;
    /** Classes compiled from the selected directory suites other than the default one. */
    private final List<String> directoryClasses;

    private SuiteClassFilter(
            Map<String, List<String>> patternSuites, List<String> selected, List<String> directoryClasses) {
        this.patternSuites = patternSuites;
        this.selected = selected;
        this.directoryClasses = directoryClasses;
    }

    /**
     * The filter for {@code suites}, the resolved selection, in a module whose {@code [test.suites]}
     * declare {@code patternSuites}. The selected directory suites' classes are named from their
     * source paths.
     */
    public static SuiteClassFilter of(
            Path moduleDir, boolean compact, List<String> suites, Map<String, List<String>> patternSuites) {
        if (patternSuites.isEmpty()) return NONE;
        List<String> directoryClasses = new ArrayList<>();
        for (String suite : suites) {
            if (TestSuites.DEFAULT.equals(suite)) continue;
            directoryClasses.addAll(sourceClasses(moduleDir, compact, suite));
        }
        return new SuiteClassFilter(
                patternSuites, List.copyOf(new LinkedHashSet<>(suites)), List.copyOf(directoryClasses));
    }

    /**
     * The regex body a compiled class name must match, whole, to run: the selected suites' own
     * classes. Null when the selection keeps every compiled class.
     */
    public @Nullable String body() {
        List<String> left = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Map.Entry<String, List<String>> suite : patternSuites.entrySet()) {
            String body = JUnitClassFilter.excludeBody(suite.getValue());
            if (body == null) continue;
            (selected.contains(suite.getKey()) ? kept : left).add(body);
        }
        boolean defaultSelected = selected.contains(TestSuites.DEFAULT);
        if (defaultSelected ? left.isEmpty() : kept.isEmpty()) return null;
        for (String name : directoryClasses) kept.add(Pattern.quote(name) + "(\\$.*)?");
        String keep = String.join("|", kept);
        if (!defaultSelected) return keep;
        String rest = "(?!(?:" + String.join("|", left) + ")$).*";
        return keep.isEmpty() ? rest : "(?:" + keep + ")|" + rest;
    }

    /**
     * One line naming the suites this run's classes come from, or null when the module declares no
     * class-pattern suite: which classes each selected pattern suite runs, and the pattern suites a
     * run of the default suite leaves out.
     */
    public @Nullable String describe() {
        if (patternSuites.isEmpty()) return null;
        List<String> ran = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (Map.Entry<String, List<String>> suite : patternSuites.entrySet()) {
            String name = suite.getKey();
            String patterns = String.join(", ", suite.getValue());
            if (selected.contains(name)) ran.add(name + " runs the test suite's classes matching " + patterns);
            else left.add(name + " (" + patterns + ")");
        }
        StringBuilder out = new StringBuilder("test suites: ").append(String.join(", ", selected));
        if (!ran.isEmpty()) out.append(" — ").append(String.join("; ", ran));
        if (!left.isEmpty() && selected.contains(TestSuites.DEFAULT)) {
            out.append(" — left out: ").append(String.join("; ", left)).append(", run with jk test --suite <name>");
        }
        return out.toString();
    }

    /**
     * The suites {@code className} belongs to when this run leaves it out: the unselected pattern
     * suites that match it, else the default suite. Empty when the run's suites own it.
     */
    public List<String> ownersLeftOut(String className) {
        if (patternSuites.isEmpty() || directoryClass(className)) return List.of();
        List<String> left = new ArrayList<>();
        for (Map.Entry<String, List<String>> suite : patternSuites.entrySet()) {
            if (!matches(suite.getValue(), className)) continue;
            if (selected.contains(suite.getKey())) return List.of();
            left.add(suite.getKey());
        }
        if (left.isEmpty()) return selected.contains(TestSuites.DEFAULT) ? List.of() : List.of(TestSuites.DEFAULT);
        return left;
    }

    /**
     * The sentence for {@code --class} patterns that name compiled classes this run's suites leave
     * out, naming the suite each belongs to; null when the patterns name none. Reads the class files
     * under {@code testClasses}.
     */
    public @Nullable String classHint(Path testClasses, List<String> classPatterns) {
        if (patternSuites.isEmpty() || classPatterns.isEmpty() || !Files.isDirectory(testClasses)) return null;
        Pattern named = Pattern.compile(JUnitClassFilter.patternRegex(classPatterns));
        Map<String, List<String>> bySuite = new LinkedHashMap<>();
        for (String className : compiledClasses(testClasses)) {
            if (!named.matcher(className).matches()) continue;
            List<String> owners = ownersLeftOut(className);
            if (owners.isEmpty()) continue;
            bySuite.computeIfAbsent(owners.getFirst(), k -> new ArrayList<>()).add(simpleName(className));
        }
        if (bySuite.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        bySuite.forEach((suite, classes) -> parts.add(String.join(
                        ", ", classes.stream().limit(3).toList())
                + (classes.size() > 3 ? " and " + (classes.size() - 3) + " more" : "")
                + (classes.size() == 1 ? " is" : " are") + " in the " + suite + " suite (jk test --suite " + suite
                + " --class " + String.join(" --class ", classPatterns) + ")"));
        return String.join("; ", parts);
    }

    private boolean directoryClass(String className) {
        for (String name : directoryClasses) {
            if (className.equals(name) || className.startsWith(name + "$")) return true;
        }
        return false;
    }

    private static boolean matches(List<String> patterns, String className) {
        String body = JUnitClassFilter.excludeBody(patterns);
        return body != null
                && Pattern.compile("^(?:" + body + ")$").matcher(className).matches();
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Top-level class names under {@code testClasses}, from their {@code .class} paths. */
    private static Set<String> compiledClasses(Path testClasses) {
        Set<String> out = new TreeSet<>();
        try {
            PathUtil.forEachRegularFile(testClasses, (file, attrs) -> {
                String rel = testClasses.relativize(file).toString().replace('\\', '/');
                if (!rel.endsWith(".class") || rel.contains("$") || rel.endsWith("module-info.class")) return;
                out.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The classes a directory suite's sources declare, by their paths under the suite's roots. */
    private static List<String> sourceClasses(Path moduleDir, boolean compact, String suite) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
        roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
        roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
        roots.addAll(TestSuites.scalaRoots(moduleDir, compact, suite));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (Path root : roots) {
                for (String ext : List.of(".java", ".kt", ".groovy", ".scala")) {
                    for (Path source : TestSuites.collectExt(root, ext)) {
                        String rel = root.relativize(source).toString().replace('\\', '/');
                        out.add(rel.substring(0, rel.length() - ext.length()).replace('/', '.'));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }
}
