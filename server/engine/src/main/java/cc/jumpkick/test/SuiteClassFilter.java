// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.ClassSuite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which compiled test classes a run's suites own when the module declares class-pattern suites
 * ({@code [test.suites.<name>]}). A class from another suite's directory belongs to that suite. A
 * class from the default suite's root belongs to every pattern suite whose {@code classes} match it
 * (nested classes go with their outer class), and to the default suite when none does. A class a
 * suite's {@code exclude-classes} also match runs in no suite.
 */
public final class SuiteClassFilter {

    /**
     * The filter of a module that runs its default suite alone, without class-pattern suites: every
     * compiled class is the run's and belongs to {@link TestSuites#DEFAULT}.
     */
    public static final SuiteClassFilter NONE = new SuiteClassFilter(Map.of(), List.of(), Map.of());

    private final Map<String, ClassSuite> patternSuites;
    /** The resolved suites, in selection order. */
    private final List<String> selected;
    /** Each class compiled from a selected directory suite other than the default one, to that suite. */
    private final Map<String, String> directoryClasses;

    private SuiteClassFilter(
            Map<String, ClassSuite> patternSuites, List<String> selected, Map<String, String> directoryClasses) {
        this.patternSuites = patternSuites;
        this.selected = selected;
        this.directoryClasses = directoryClasses;
    }

    /**
     * The filter for {@code suites}, the resolved selection, in a module whose {@code [test.suites]}
     * declare {@code patternSuites} (empty when none). The selected directory suites' classes are
     * named from their source paths.
     */
    public static SuiteClassFilter of(
            Path moduleDir, boolean compact, List<String> suites, Map<String, ClassSuite> patternSuites) {
        if (patternSuites.isEmpty() && (suites.isEmpty() || suites.equals(List.of(TestSuites.DEFAULT)))) return NONE;
        Map<String, String> directoryClasses = new LinkedHashMap<>();
        for (String suite : suites) {
            if (TestSuites.DEFAULT.equals(suite)) continue;
            for (String name : sourceClasses(moduleDir, compact, suite)) directoryClasses.putIfAbsent(name, suite);
        }
        return new SuiteClassFilter(
                patternSuites, List.copyOf(new LinkedHashSet<>(suites)), Collections.unmodifiableMap(directoryClasses));
    }

    /**
     * The suite {@code className} (a binary name; nested classes go with their outer class) runs in:
     * the directory suite whose sources declare it, else the first selected pattern suite that
     * runs it, else the default suite.
     */
    public String suiteOf(String className) {
        String directory = directoryOwner(className);
        if (directory != null) return directory;
        for (Map.Entry<String, ClassSuite> suite : patternSuites.entrySet()) {
            if (selected.contains(suite.getKey()) && runs(suite.getValue(), className)) return suite.getKey();
        }
        return TestSuites.DEFAULT;
    }

    /**
     * The regex body a compiled class name must match, whole, to run: the selected suites' own
     * classes. Null when the selection keeps every compiled class.
     */
    public @Nullable String body() {
        List<String> left = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Map.Entry<String, ClassSuite> suite : patternSuites.entrySet()) {
            String classes = JUnitClassFilter.excludeBody(suite.getValue().classes());
            if (classes == null) continue;
            String excluded = JUnitClassFilter.excludeBody(suite.getValue().excludeClasses());
            if (!selected.contains(suite.getKey())) {
                left.add(classes);
            } else if (excluded == null) {
                kept.add(classes);
            } else {
                kept.add("(?!(?:" + excluded + ")$)(?:" + classes + ")");
                left.add("(?=(?:" + excluded + ")$)(?:" + classes + ")");
            }
        }
        boolean defaultSelected = selected.contains(TestSuites.DEFAULT);
        if (defaultSelected ? left.isEmpty() : kept.isEmpty()) return null;
        for (String name : directoryClasses.keySet()) kept.add(Pattern.quote(name) + "(\\$.*)?");
        String keep = String.join("|", kept);
        if (!defaultSelected) return keep;
        String rest = "(?!(?:" + String.join("|", left) + ")$).*";
        return keep.isEmpty() ? rest : "(?:" + keep + ")|" + rest;
    }

    /** Whether this run's suites run a class, by {@link #body}; always true when it keeps every class. */
    public Predicate<String> acceptor() {
        String body = body();
        if (body == null) return name -> true;
        Pattern pattern = Pattern.compile("^(?:" + body + ")$");
        return name -> pattern.matcher(name).matches();
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
        for (Map.Entry<String, ClassSuite> suite : patternSuites.entrySet()) {
            String name = suite.getKey();
            String patterns = String.join(", ", suite.getValue().classes());
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
        if (patternSuites.isEmpty() || directoryOwner(className) != null) return List.of();
        List<String> left = new ArrayList<>();
        for (Map.Entry<String, ClassSuite> suite : patternSuites.entrySet()) {
            if (!matches(suite.getValue().classes(), className)) continue;
            if (matches(suite.getValue().excludeClasses(), className)) return List.of();
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

    private @Nullable String directoryOwner(String className) {
        int nested = className.indexOf('$');
        return directoryClasses.get(nested < 0 ? className : className.substring(0, nested));
    }

    /** Whether {@code suite} runs {@code className}: its classes match and its exclusions do not. */
    private static boolean runs(ClassSuite suite, String className) {
        return matches(suite.classes(), className) && !matches(suite.excludeClasses(), className);
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
