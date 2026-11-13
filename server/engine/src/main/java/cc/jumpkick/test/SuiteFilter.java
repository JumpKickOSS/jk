// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which tests a run's suites own, in a module with more than its default suite.
 *
 * <p>A class compiled from another suite's directory belongs to that suite. A class of the default
 * suite's sources belongs to every declared suite whose {@code classes} match it (nested classes go
 * with their outer class). A test carrying one of a declared suite's {@code tags} belongs to that
 * suite, whichever source it comes from, and to no undeclared suite: tags are a cost, wherever the
 * test lives. The default suite keeps what no declared suite claims by class or tag. A test a
 * suite's {@code exclude-classes} or {@code exclude-tags} also names stays claimed and runs in no
 * suite.
 *
 * <p>The engine sees classes, not tests: {@link #body} is the class names the run may touch, and
 * {@link #members} the exact rule, by class and tag, the test runner applies at discovery.
 */
public final class SuiteFilter {

    /**
     * The filter of a module that runs its default suite alone, without declared suites: every
     * compiled class is the run's and belongs to {@link TestSuites#DEFAULT}.
     */
    public static final SuiteFilter NONE =
            new SuiteFilter(Map.of(), List.of(TestSuites.DEFAULT), Map.of(), Set.of(), null, null, name -> null);

    private final Map<String, DeclaredSuite> declared;
    /** The resolved suites, in selection order. */
    private final List<String> selected;
    /** Each class compiled from a directory suite other than the default one, to that suite. */
    private final Map<String, String> directoryClasses;
    /** The directory groups compiled: {@link TestSuites#DEFAULT} for the default suite's sources, each suite directory its own. */
    private final Set<String> groups;
    /** The directory group this filter runs beside, when it is a {@link #part}; null for the whole selection. */
    private final @Nullable String part;
    /** The selected suites this filter runs, when it is one {@link #only} launch of several; null for all of them. */
    private final @Nullable Set<String> runs;

    private final Function<String, @Nullable ClassTags> tags;

    private SuiteFilter(
            Map<String, DeclaredSuite> declared,
            List<String> selected,
            Map<String, String> directoryClasses,
            Set<String> groups,
            @Nullable String part,
            @Nullable Set<String> runs,
            Function<String, @Nullable ClassTags> tags) {
        this.declared = declared;
        this.selected = selected;
        this.directoryClasses = directoryClasses;
        this.groups = groups;
        this.part = part;
        this.runs = runs;
        this.tags = tags;
    }

    /**
     * The filter for {@code suites}, the resolved selection, in a module whose {@code [test.suites]}
     * declare {@code declared} (empty when none). The compiled directory suites' classes are named
     * from their source paths.
     */
    public static SuiteFilter of(
            Path moduleDir, boolean compact, List<String> suites, Map<String, DeclaredSuite> declared) {
        if (declared.isEmpty() && (suites.isEmpty() || suites.equals(List.of(TestSuites.DEFAULT)))) return NONE;
        List<String> compiled = TestSuites.compiled(moduleDir, compact, suites, declared);
        Map<String, String> directoryClasses = new LinkedHashMap<>();
        Set<String> groups = new LinkedHashSet<>();
        for (String suite : compiled) {
            groups.add(suite);
            if (TestSuites.DEFAULT.equals(suite)) continue;
            for (String name : sourceClasses(moduleDir, compact, suite)) directoryClasses.putIfAbsent(name, suite);
        }
        return new SuiteFilter(
                new LinkedHashMap<>(declared),
                List.copyOf(new LinkedHashSet<>(suites)),
                Collections.unmodifiableMap(directoryClasses),
                Collections.unmodifiableSet(groups),
                null,
                null,
                name -> null);
    }

    /**
     * This filter reading each class's tags from {@code lookup} (null for a class it cannot read),
     * so the engine-side answers ({@link #acceptor}, {@link #suiteOf}, {@link #ownersLeftOut}) see
     * tag claims; without it a tag claim is a possibility the runner settles.
     */
    public SuiteFilter withTags(Function<String, @Nullable ClassTags> lookup) {
        if (claimedTags().isEmpty()) return this;
        return new SuiteFilter(declared, selected, directoryClasses, groups, part, runs, lookup);
    }

    /**
     * The part of this selection that runs beside {@code directorySuite}'s own resources: that
     * suite's classes alone, or for the default suite every class of its sources, never a class a
     * suite directory declares. Null when no selected suite runs anything there.
     */
    public @Nullable SuiteFilter part(String directorySuite) {
        if (running().stream().noneMatch(suite -> contributes(suite, directorySuite))) return null;
        return new SuiteFilter(declared, selected, directoryClasses, groups, directorySuite, runs, tags);
    }

    /**
     * This filter running only {@code suites} of the selection, as one of {@link #launches}; null
     * when none of them runs anything in this filter's {@link #part}.
     */
    public @Nullable SuiteFilter only(Set<String> suites) {
        Set<String> kept = new LinkedHashSet<>();
        for (String suite : running()) {
            if (suites.contains(suite) && (part == null || contributes(suite, part))) kept.add(suite);
        }
        if (kept.isEmpty()) return null;
        return new SuiteFilter(declared, selected, directoryClasses, groups, part, Set.copyOf(kept), tags);
    }

    /** One set of the selection's suites that run in the same test JVMs, under {@code settings}. */
    public record Launch(Set<String> suites, SuiteSettings settings) {}

    /**
     * How the selection's suites share test JVMs: the suites without settings of their own in one
     * launch, then each suite with settings alone, since its env and flags are its JVMs' only. A
     * test two launches' suites claim runs in each.
     */
    public List<Launch> launches() {
        Set<String> shared = new LinkedHashSet<>();
        List<Launch> own = new ArrayList<>();
        for (String suite : selected) {
            DeclaredSuite spec = declared.get(suite);
            if (spec == null || spec.settings().isEmpty()) shared.add(suite);
            else own.add(new Launch(Set.of(suite), spec.settings()));
        }
        List<Launch> out = new ArrayList<>();
        if (!shared.isEmpty()) out.add(new Launch(Collections.unmodifiableSet(shared), SuiteSettings.NONE));
        out.addAll(own);
        return out;
    }

    /** The selected suites this filter runs. */
    private List<String> running() {
        Set<String> only = runs;
        return only == null
                ? selected
                : selected.stream().filter(only::contains).toList();
    }

    /**
     * The suite {@code className} (a binary name; nested classes go with their outer class) runs in:
     * the first selected declared suite that claims it, else the directory suite whose sources
     * declare it, else the default suite, else the first selected suite.
     */
    public String suiteOf(String className) {
        String directory = directoryOwner(className);
        ClassTags known = tags.apply(outer(className));
        for (String name : declared.keySet()) {
            if (selected.contains(name)
                    && claims(name, className, directory, known)
                    && !excludes(name, className, known)) return name;
        }
        if (directory != null && !declared.containsKey(directory) && selected.contains(directory)) return directory;
        if (directory == null && selected.contains(TestSuites.DEFAULT)) return TestSuites.DEFAULT;
        return selected.isEmpty() ? TestSuites.DEFAULT : selected.getFirst();
    }

    /**
     * The regex body a compiled class name must match, whole, for the run to look at it: every class
     * holding a test of the selected suites. Null when the run looks at every compiled class.
     */
    public @Nullable String body() {
        Cls any = Cls.NONE;
        for (Clause clause : clauses()) any = or(any, clause.classes());
        return any == Cls.ALL ? null : regex(any);
    }

    /**
     * The runner's membership rule, one {@code --member} value each: {@code <any tags>|<no tags>|<class
     * regex>}, comma-separated tag lists. A test runs when one holds: its class matches the regex,
     * it carries one of the first tags (when there are any) and none of the second. Empty when no
     * declared suite claims a tag, as {@link #body} then decides alone.
     */
    public List<String> members() {
        if (claimedTags().isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Clause clause : clauses()) {
            out.add(String.join(",", clause.any()) + "|" + String.join(",", clause.none()) + "|"
                    + regex(clause.classes()));
        }
        return out;
    }

    /**
     * Whether a fully qualified class name is one {@code --class} {@code patterns} select, by the
     * runner's own grammar (a dotted name is qualified, a bare one any package's, {@code *} any run
     * of characters, a {@code #method} suffix its class); nothing when no pattern names a class.
     */
    public static Predicate<String> selects(List<String> patterns) {
        String re = JUnitClassFilter.patternRegex(patterns);
        if ("^()$".equals(re)) return name -> false;
        Pattern pattern = Pattern.compile(re);
        return name -> pattern.matcher(name).matches();
    }

    /**
     * Whether this run's suites may run a test of a class: by {@link #members} over the tags
     * {@link #withTags} reads, a tag unread counting as present. Always true when the run keeps
     * every class.
     */
    public Predicate<String> acceptor() {
        List<Clause> clauses = clauses();
        String body = body();
        if (body == null && claimedTags().isEmpty()) return name -> true;
        List<Pattern> patterns = new ArrayList<>();
        for (Clause clause : clauses) patterns.add(Pattern.compile("^(?:" + regex(clause.classes()) + ")$"));
        return name -> {
            ClassTags known = tags.apply(outer(name));
            for (int i = 0; i < clauses.size(); i++) {
                if (patterns.get(i).matcher(name).matches() && clauses.get(i).admits(known)) return true;
            }
            return false;
        };
    }

    /**
     * One line naming the suites this run's tests come from, or null when the module declares no
     * suite: what each selected declared suite runs, and the declared suites a run of the default
     * suite leaves out.
     */
    public @Nullable String describe() {
        if (declared.isEmpty()) return null;
        List<String> ran = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (Map.Entry<String, DeclaredSuite> suite : declared.entrySet()) {
            String name = suite.getKey();
            DeclaredSuite spec = suite.getValue();
            if (selected.contains(name)) {
                List<String> runs = new ArrayList<>();
                if (!spec.classes().isEmpty()) runs.add("test classes matching " + String.join(", ", spec.classes()));
                if (!spec.tags().isEmpty()) runs.add("tests tagged " + String.join(", ", spec.tags()));
                ran.add(name + " runs " + String.join(" and ", runs));
            } else {
                List<String> claims = new ArrayList<>(spec.classes());
                if (!spec.tags().isEmpty()) claims.add("tagged " + String.join(", ", spec.tags()));
                left.add(name + " (" + String.join(", ", claims) + ")");
            }
        }
        StringBuilder out = new StringBuilder("test suites: ").append(String.join(", ", selected));
        if (!ran.isEmpty()) out.append("; ").append(String.join("; ", ran));
        if (!left.isEmpty() && selected.contains(TestSuites.DEFAULT)) {
            out.append("; not run: ").append(String.join(", ", left)).append(", use jk test -s <name>");
        }
        return out.toString();
    }

    /**
     * The suites {@code className} belongs to when this run leaves it out: the unselected declared
     * suites that claim it, else the default suite. Empty when the run's suites own it, or when it
     * is claimed and excluded and so runs nowhere.
     */
    public List<String> ownersLeftOut(String className) {
        String directory = directoryOwner(className);
        if (declared.isEmpty() || (directory != null && !declared.containsKey(directory))) return List.of();
        ClassTags known = tags.apply(outer(className));
        List<String> left = new ArrayList<>();
        for (String name : declared.keySet()) {
            if (!claims(name, className, directory, known)) continue;
            if (excludes(name, className, known)) return List.of();
            if (selected.contains(name)) return List.of();
            left.add(name);
        }
        if (left.isEmpty()) return selected.contains(TestSuites.DEFAULT) ? List.of() : List.of(TestSuites.DEFAULT);
        return left;
    }

    /**
     * The sentence for {@code --class} patterns that name compiled classes this run's suites leave
     * out, naming the suite each belongs to; null when the patterns name none. Reads the class files
     * under {@code testClasses}, their tags included.
     */
    public @Nullable String classHint(Path testClasses, List<String> classPatterns) {
        if (declared.isEmpty() || classPatterns.isEmpty() || !Files.isDirectory(testClasses)) return null;
        SuiteFilter read = claimedTags().isEmpty() ? this : withTags(classTagsIn(testClasses));
        Pattern named = Pattern.compile(JUnitClassFilter.patternRegex(classPatterns));
        Map<String, List<String>> bySuite = new LinkedHashMap<>();
        for (String className : compiledClasses(testClasses)) {
            if (!named.matcher(className).matches()) continue;
            List<String> owners = read.ownersLeftOut(className);
            if (owners.isEmpty()) continue;
            bySuite.computeIfAbsent(owners.getFirst(), k -> new ArrayList<>()).add(simpleName(className));
        }
        if (bySuite.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        bySuite.forEach((suite, classes) ->
                parts.add(String.join(", ", classes.stream().limit(3).toList())
                        + (classes.size() > 3 ? " and " + (classes.size() - 3) + " more" : "")
                        + (classes.size() == 1 ? " is" : " are") + " in the " + suite + " suite (jk test -s " + suite
                        + " -S " + String.join(" -S ", classPatterns) + ")"));
        return String.join("; ", parts);
    }

    /**
     * True when a class compiled under {@code testClasses} passes the class filter the runner would
     * apply: it matches {@code classPatterns}, this run's suites may own it and {@code
     * excludeClasses} does not name it. The judgement a forked discovery would reach on class names,
     * read from the class files instead, so a module the patterns name nothing in skips without a
     * test JVM.
     */
    public boolean namesCompiledClass(Path testClasses, List<String> classPatterns, List<String> excludeClasses) {
        if (classPatterns.isEmpty() || !Files.isDirectory(testClasses)) return false;
        Pattern accepted = Pattern.compile(JUnitClassFilter.filter(
                JUnitClassFilter.patternRegex(classPatterns), JUnitClassFilter.excludeBody(excludeClasses), body()));
        return compiledClasses(testClasses, true).stream()
                .anyMatch(name -> accepted.matcher(name).matches());
    }

    /** Every tag a declared suite claims: the tags the default and undeclared directory suites leave out. */
    Set<String> claimedTags() {
        Set<String> out = new TreeSet<>();
        for (DeclaredSuite suite : declared.values()) out.addAll(suite.tags());
        return out;
    }

    /**
     * The classes whose tags a run's {@link #withTags} reads: the tags of each top-level class
     * compiled under {@code testClasses}, as {@link TestClassIndex} reads them, on first use.
     */
    public static Function<String, @Nullable ClassTags> classTagsIn(Path testClasses) {
        Map<String, ClassTags> byClass = new HashMap<>();
        boolean[] read = {false};
        return name -> {
            synchronized (byClass) {
                if (!read[0]) {
                    read[0] = true;
                    try {
                        for (TestClassIndex.Entry entry : TestClassIndex.scan(testClasses, Set.of())) {
                            byClass.put(entry.className(), new ClassTags(entry.classTags(), entry.tags()));
                        }
                    } catch (IOException | UncheckedIOException unreadable) {
                        // No tags known: every tag claim stays a possibility.
                    }
                }
                return byClass.get(name);
            }
        };
    }

    // ---- membership ----

    /** One way a test is the run's: its class matches, it carries one of {@code any} (when not empty) and none of {@code none}. */
    private record Clause(Cls classes, List<String> any, List<String> none) {

        /** Whether a class with {@code known} tags may hold a test this clause admits; unknown tags admit. */
        boolean admits(@Nullable ClassTags known) {
            if (known == null) return true;
            for (String tag : none) {
                if (known.classLevel().contains(tag)) return false;
            }
            if (any.isEmpty()) return true;
            for (String tag : any) {
                if (known.all().contains(tag)) return true;
            }
            return false;
        }
    }

    /** The selected suites' clauses within this filter's {@link #part}, those that can match no class left out. */
    private List<Clause> clauses() {
        Cls within = within();
        Set<String> claimed = claimedTags();
        List<Clause> out = new ArrayList<>();
        for (String suite : running()) {
            if (part != null && !contributes(suite, part)) continue;
            DeclaredSuite spec = declared.get(suite);
            if (spec == null) {
                Cls classes = TestSuites.DEFAULT.equals(suite) ? defaultClasses() : directory(suite);
                add(out, and(within, classes), Set.of(), claimed);
                continue;
            }
            Cls kept = not(rx(JUnitClassFilter.excludeBody(spec.excludeClasses())));
            Set<String> none = new TreeSet<>(spec.excludeTags());
            Cls byClass = or(directory(suite), and(defaultSources(), rx(JUnitClassFilter.excludeBody(spec.classes()))));
            add(out, and(within, kept, byClass), Set.of(), none);
            if (!spec.tags().isEmpty()) add(out, and(within, kept), new TreeSet<>(spec.tags()), none);
        }
        return out;
    }

    private static void add(List<Clause> out, Cls classes, Set<String> any, Set<String> none) {
        if (classes != Cls.NONE)
            out.add(new Clause(classes, List.copyOf(new TreeSet<>(any)), List.copyOf(new TreeSet<>(none))));
    }

    /** Whether {@code suite} runs tests beside directory group {@code group}'s resources: its own directory, or what it claims there. */
    private boolean contributes(String suite, String group) {
        DeclaredSuite spec = declared.get(suite);
        if (suite.equals(group)) return true;
        if (spec == null) return false;
        // Tags claim from every source; class patterns from the default suite's.
        return !spec.tags().isEmpty()
                || (TestSuites.DEFAULT.equals(group) && !spec.classes().isEmpty());
    }

    /** The classes this filter's part holds: every class, or one directory group's. */
    private Cls within() {
        if (part == null) return Cls.ALL;
        if (!TestSuites.DEFAULT.equals(part)) return directory(part);
        Cls others = Cls.NONE;
        for (String group : groups) {
            if (!TestSuites.DEFAULT.equals(group)) others = or(others, directory(group));
        }
        return not(others);
    }

    /** The default suite's own classes: of its sources, matching no declared suite's {@code classes}. */
    private Cls defaultClasses() {
        Cls claimed = Cls.NONE;
        for (DeclaredSuite spec : declared.values()) {
            claimed = or(claimed, rx(JUnitClassFilter.excludeBody(spec.classes())));
        }
        return and(defaultSources(), not(claimed));
    }

    /** The classes of the default suite's sources: compiled from no directory suite. */
    private Cls defaultSources() {
        if (soleDirectory() != null) return Cls.NONE;
        return directoryClasses.isEmpty() ? Cls.ALL : not(rx(quotedWithNested(directoryClasses.keySet())));
    }

    /** The classes directory suite {@code suite}'s sources declare; every class when it is the only source compiled. */
    private Cls directory(String suite) {
        if (suite.equals(soleDirectory())) return Cls.ALL;
        Set<String> names = new LinkedHashSet<>();
        directoryClasses.forEach((name, owner) -> {
            if (owner.equals(suite)) names.add(name);
        });
        return names.isEmpty() ? Cls.NONE : rx(quotedWithNested(names));
    }

    /** The one directory suite compiled when the run compiles nothing else, the default suite's sources included; else null. */
    private @Nullable String soleDirectory() {
        if (groups.size() != 1) return null;
        String only = groups.iterator().next();
        return TestSuites.DEFAULT.equals(only) ? null : only;
    }

    /** Whether declared suite {@code name} claims {@code className}, by directory, class pattern or a tag {@code known} holds. */
    private boolean claims(String name, String className, @Nullable String directory, @Nullable ClassTags known) {
        DeclaredSuite spec = declared.get(name);
        if (spec == null) return false;
        if (name.equals(directory)) return true;
        if (directory == null && matches(spec.classes(), className)) return true;
        if (known == null) return false;
        for (String tag : spec.tags()) {
            if (known.all().contains(tag)) return true;
        }
        return false;
    }

    /** Whether declared suite {@code name}'s exclusions leave {@code className} out whole. */
    private boolean excludes(String name, String className, @Nullable ClassTags known) {
        DeclaredSuite spec = declared.get(name);
        if (spec == null) return false;
        if (matches(spec.excludeClasses(), className)) return true;
        if (known == null) return false;
        for (String tag : spec.excludeTags()) {
            if (known.classLevel().contains(tag)) return true;
        }
        return false;
    }

    private @Nullable String directoryOwner(String className) {
        return directoryClasses.get(outer(className));
    }

    private static String outer(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static boolean matches(List<String> patterns, String className) {
        String body = JUnitClassFilter.excludeBody(patterns);
        return body != null
                && Pattern.compile("^(?:" + body + ")$").matcher(className).matches();
    }

    private static String quotedWithNested(Set<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) out.add(Pattern.quote(name));
        return "(?:" + String.join("|", out) + ")(?:\\$.*)?";
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    // ---- class-name sets as regexes ----

    /** A set of class names: everything, nothing, a regex body, or a combination. */
    private sealed interface Cls permits Cls.Const, Rx, Not, And, Or {
        Cls ALL = Const.ALL;
        Cls NONE = Const.NONE;

        enum Const implements Cls {
            ALL,
            NONE
        }
    }

    private record Rx(String body) implements Cls {}

    private record Not(Cls of) implements Cls {}

    private record And(List<Cls> of) implements Cls {}

    private record Or(List<Cls> of) implements Cls {}

    private static Cls rx(@Nullable String body) {
        return body == null ? Cls.NONE : new Rx(body);
    }

    private static Cls not(Cls x) {
        if (x == Cls.ALL) return Cls.NONE;
        if (x == Cls.NONE) return Cls.ALL;
        if (x instanceof Not(Cls of)) return of;
        return new Not(x);
    }

    private static Cls and(Cls... xs) {
        LinkedHashSet<Cls> terms = new LinkedHashSet<>();
        for (Cls x : xs) {
            if (x instanceof And(List<Cls> of)) terms.addAll(of);
            else terms.add(x);
        }
        terms.remove(Cls.ALL);
        if (terms.contains(Cls.NONE)) return Cls.NONE;
        for (Cls term : terms) {
            if (terms.contains(not(term))) return Cls.NONE;
        }
        if (terms.isEmpty()) return Cls.ALL;
        return terms.size() == 1 ? terms.iterator().next() : new And(List.copyOf(terms));
    }

    private static Cls or(Cls... xs) {
        LinkedHashSet<Cls> terms = new LinkedHashSet<>();
        for (Cls x : xs) {
            if (x instanceof Or(List<Cls> of)) terms.addAll(of);
            else terms.add(x);
        }
        terms.remove(Cls.NONE);
        if (terms.contains(Cls.ALL)) return Cls.ALL;
        for (Cls term : terms) {
            if (terms.contains(not(term))) return Cls.ALL;
            // x | y | !(x | y): a run of the default suite beside every suite it leaves out.
            if (term instanceof Not(Or(List<Cls> of)) && terms.containsAll(of)) return Cls.ALL;
        }
        if (terms.isEmpty()) return Cls.NONE;
        return terms.size() == 1 ? terms.iterator().next() : new Or(List.copyOf(terms));
    }

    private static String regex(Cls x) {
        return switch (x) {
            case Cls.Const c -> c == Cls.Const.ALL ? ".*" : "(?!)";
            case Rx(String body) -> body;
            case Not(Cls of) -> "(?!(?:" + regex(of) + ")$).*";
            case And(List<Cls> of) -> {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < of.size() - 1; i++)
                    out.append("(?=(?:").append(regex(of.get(i))).append(")$)");
                yield out.append("(?:").append(regex(of.getLast())).append(')').toString();
            }
            case Or(List<Cls> of) -> {
                List<String> parts = new ArrayList<>();
                for (Cls term : of) parts.add("(?:" + regex(term) + ")");
                yield String.join("|", parts);
            }
        };
    }

    // ---- class files and sources ----

    /** Top-level class names under {@code testClasses}, from their {@code .class} paths. */
    private static Set<String> compiledClasses(Path testClasses) {
        return compiledClasses(testClasses, false);
    }

    /** Binary class names under {@code testClasses}, nested classes included when {@code nested}. */
    private static Set<String> compiledClasses(Path testClasses, boolean nested) {
        Set<String> out = new TreeSet<>();
        try {
            PathUtil.forEachRegularFile(testClasses, (file, attrs) -> {
                String rel = testClasses.relativize(file).toString().replace('\\', '/');
                if (!rel.endsWith(".class") || rel.endsWith("module-info.class")) return;
                if (!nested && rel.contains("$")) return;
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
