// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.JarFacts;
import cc.jumpkick.guard.extract.SourceRoots;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What moving a dependency from its locked version to another takes away from its public API, and
 * where this project's compiled code uses each thing taken away. The surface is the public and
 * protected classes, methods, constructors and fields of the dependency's own jar; a member counts
 * as gone when no class of the new jar on the old one's supertype path declares it. A call whose
 * owner is the project's own subclass of a library class is followed up to the library class.
 */
public final class ApiBreaks {

    private ApiBreaks() {}

    /** How a member left the API. */
    public enum Kind {
        REMOVED,
        RENAMED,
        SIGNATURE_CHANGED;

        String label() {
            return switch (this) {
                case REMOVED -> "removed";
                case RENAMED -> "renamed";
                case SIGNATURE_CHANGED -> "signature changed";
            };
        }
    }

    /**
     * One member (or whole class, {@code member} empty) of the old API gone from the new one.
     * {@code owner} is the internal class name, {@code member} is {@code name(desc)} for a method,
     * the name for a field; {@code now} is what the new jar has in its place, empty when nothing.
     */
    public record Change(Kind kind, String owner, String member, boolean field, List<String> now) {

        public Change {
            now = List.copyOf(now);
        }

        /** {@code a.b.C}, {@code a.b.C.m(String, int)} or {@code a.b.C.FIELD}. */
        public String display() {
            String cls = Descriptors.binaryName(owner).replace('$', '.');
            if (member.isEmpty()) return cls;
            return cls + "." + (field ? member : method(member));
        }

        private String key() {
            return owner + "#" + member;
        }
    }

    /** One use of a changed member: the workspace-relative source, its line (0 when unknown) and the using member. */
    public record Site(String file, int line, String from) {

        String at() {
            return line > 0 ? file + ":" + line : file;
        }
    }

    /** A project's compiled classes and the workspace-relative source each came from, {@code null} when unknown. */
    public record Classes(FactsIndex facts, Function<ClassFacts, @Nullable String> file) {}

    /**
     * The move of {@code module} ({@code group:artifact}) from {@code from} to {@code to}: every
     * change, and the project's uses of the ones it uses, in the order {@link #diff} lists them.
     */
    public record Report(String module, String from, String to, List<Change> changes, Map<Change, List<Site>> sites) {

        public Report {
            changes = List.copyOf(changes);
            sites = Map.copyOf(sites);
        }

        /** How many places in this project use something the move takes away. */
        public int breaks() {
            int n = 0;
            for (List<Site> s : sites.values()) n += s.size();
            return n;
        }

        /**
         * The report as text, at most {@code rows} lines of changes and places: the changes this
         * project uses first, each with its places, then the others.
         */
        public String text(int rows) {
            List<String> out = new ArrayList<>();
            long used = changes.stream().filter(sites::containsKey).count();
            out.add(module + " " + from + " → " + to + ": " + count(changes.size(), "API change") + ", " + used
                    + " used here at " + count(breaks(), "place"));
            int budget = rows;
            int left = 0;
            for (Change c : changes) {
                List<Site> at = sites.get(c);
                if (at == null) continue;
                if (budget <= 0) {
                    left++;
                    continue;
                }
                out.add(line(c));
                budget--;
                for (Site s : at) {
                    if (budget <= 0) break;
                    out.add("    " + s.at() + "  " + s.from());
                    budget--;
                }
            }
            for (Change c : changes) {
                if (sites.containsKey(c)) continue;
                if (budget-- <= 0) {
                    left++;
                    continue;
                }
                out.add(line(c));
            }
            if (left > 0) out.add("… " + count(left, "more change"));
            return String.join("\n", out);
        }

        private static String line(Change c) {
            String now = c.now().isEmpty() ? "" : "  now " + String.join(", ", c.now());
            return "  " + c.kind().label() + "  " + c.display() + now;
        }
    }

    /**
     * The move of {@code module} from the version the lock at {@code lockDir} pins to {@code to}:
     * the locked jar from the store, the target's fetched through {@code repos}, the uses read from
     * {@code project} ({@link #workspace}). {@code null} when
     * the lock pins no jar of {@code module} the store holds, or the target has no jar.
     */
    public static @Nullable Report of(
            Path lockDir, String module, String to, RepoGroup repos, Cas cas, List<Classes> project)
            throws IOException, InterruptedException {
        ClasspathResolver.Entry locked = locked(lockDir, module, cas);
        if (locked == null || locked.jar() == null) return null;
        String from = locked.artifact().version();
        Coordinate target = Coordinate.ofModule(module, to);
        Path toJar =
                repos.tryFetchArtifact(target).map(f -> f.fetched().cachePath()).orElse(null);
        if (toJar == null) return null;
        Path cache = cas.root().resolve("api-diff");
        FactsIndex before = JarFacts.of(locked.jar(), cache);
        FactsIndex after = JarFacts.of(toJar, cache);
        List<Change> changes = diff(before, after);
        return new Report(module, from, to, changes, join(changes, before, project));
    }

    /** The lock's row for {@code module}'s main jar, when the store holds it. */
    private static ClasspathResolver.@Nullable Entry locked(Path lockDir, String module, Cas cas) throws IOException {
        Path lock = lockDir.resolve(ManifestNames.LOCK);
        if (!Files.isRegularFile(lock)) return null;
        Lockfile lf = LockfileReader.read(lock);
        for (ClasspathResolver.Entry e : new ClasspathResolver(cas).entriesOnDisk(lf, EnumSet.allOf(Scope.class))) {
            String[] name = e.artifact().name().split(":", -1);
            if (name.length < 2 || !(name[0] + ":" + name[1]).equals(module)) continue;
            if (name.length > 2 && !name[2].isEmpty() && !name[2].equals("jar")) continue;
            Path jar = e.jar();
            if (jar != null && jar.getFileName().toString().endsWith(".jar")) return e;
        }
        return null;
    }

    /** Every module's main, test and guard classes under {@code root}, as its last compile left them. */
    public static List<Classes> workspace(Path root) {
        List<Classes> out = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> module : GraphRunner.modules(root).entrySet()) {
            Path dir = module.getKey();
            BuildLayout layout = BuildLayout.of(root, dir, module.getValue());
            String rel = root.relativize(dir).toString().replace('\\', '/');
            String prefix = rel.isEmpty() ? "" : rel + "/";
            for (Map.Entry<String, Path> set : GraphRunner.classSets(layout).entrySet()) {
                if (!Files.isDirectory(set.getValue())) continue;
                try {
                    FactsIndex facts = FactsIndexing.load(FactsIndexing.ensure(
                            set.getValue(), FactsIndexing.indexPath(layout.buildDir(), set.getKey())));
                    out.add(new Classes(facts, c -> source(dir, prefix, c)));
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("api diff: no class facts in " + set.getValue(), unreadable);
                }
            }
        }
        return out;
    }

    private static @Nullable String source(Path moduleDir, String prefix, ClassFacts c) {
        String tail = SourceRoots.tail(c);
        if (tail == null) return null;
        String root = SourceRoots.rootHolding(moduleDir, tail);
        return root == null ? null : prefix + root + "/" + tail;
    }

    // ---- the diff ----------------------------------------------------------------

    /**
     * What {@code after} no longer offers of {@code before}'s public API: each class gone (renamed
     * when one new class of the same simple name appeared elsewhere), then, per remaining class, each
     * method and field gone (signature changed when a member of the same name remains, renamed when
     * exactly one new member of the same type takes its place), ordered by class and member.
     */
    public static List<Change> diff(FactsIndex before, FactsIndex after) {
        List<Change> out = new ArrayList<>();
        Map<String, ClassFacts> was = surface(before);
        Map<String, ClassFacts> is = surface(after);
        Map<String, List<String>> appeared = new HashMap<>();
        for (String name : is.keySet())
            if (!was.containsKey(name))
                appeared.computeIfAbsent(simple(name), k -> new ArrayList<>()).add(name);
        List<String> names = new ArrayList<>(was.keySet());
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            ClassFacts old = was.get(name);
            ClassFacts now = is.get(name);
            if (old == null) continue;
            if (now == null) {
                List<String> moved = appeared.getOrDefault(simple(name), List.of());
                out.add(
                        moved.size() == 1
                                ? new Change(
                                        Kind.RENAMED,
                                        name,
                                        "",
                                        false,
                                        List.of(Descriptors.binaryName(moved.getFirst())
                                                .replace('$', '.')))
                                : new Change(Kind.REMOVED, name, "", false, List.of()));
                continue;
            }
            methods(out, old, now, after);
            fields(out, old, now, after);
        }
        return out;
    }

    private static void methods(List<Change> out, ClassFacts was, ClassFacts is, FactsIndex after) {
        Set<String> hadNames = new HashSet<>();
        for (MethodFacts m : was.methods()) hadNames.add(m.name());
        List<MethodFacts> gone = new ArrayList<>();
        for (MethodFacts m : was.methods()) {
            if (!exposed(m.access()) || (m.access() & BRIDGE) != 0 || m.name().equals("<clinit>")) continue;
            MethodFacts found = findMethod(after, is.name(), m, m.name().equals("<init>"));
            if (found == null || isStatic(found.access()) != isStatic(m.access())) gone.add(m);
        }
        gone.sort(Comparator.comparing(MethodFacts::member));
        Map<MethodFacts, List<String>> same = new HashMap<>();
        Map<MethodFacts, List<String>> renamed = new HashMap<>();
        Map<String, Integer> claimed = new HashMap<>();
        for (MethodFacts m : gone) {
            for (MethodFacts n : is.methods()) {
                if (exposed(n.access())
                        && (n.access() & BRIDGE) == 0
                        && n.name().equals(m.name()))
                    same.computeIfAbsent(m, k -> new ArrayList<>()).add(method(n.member()));
            }
            if (same.containsKey(m)) continue;
            for (MethodFacts n : is.methods()) {
                if (!exposed(n.access())
                        || (n.access() & BRIDGE) != 0
                        || n.name().startsWith("<")) continue;
                if (n.desc().equals(m.desc()) && !hadNames.contains(n.name())) {
                    renamed.computeIfAbsent(m, k -> new ArrayList<>()).add(n.member());
                    claimed.merge(n.member(), 1, Integer::sum);
                }
            }
        }
        for (MethodFacts m : gone) {
            String member = m.name() + m.desc();
            List<String> sameName = same.getOrDefault(m, List.of());
            List<String> other = renamed.getOrDefault(m, List.of());
            // A rename only when one new name takes the place of one gone name, both ways.
            boolean rename = other.size() == 1 && claimed.getOrDefault(other.getFirst(), 0) == 1;
            if (!sameName.isEmpty()) out.add(new Change(Kind.SIGNATURE_CHANGED, was.name(), member, false, sameName));
            else if (rename)
                out.add(new Change(Kind.RENAMED, was.name(), member, false, List.of(method(other.getFirst()))));
            else out.add(new Change(Kind.REMOVED, was.name(), member, false, List.of()));
        }
    }

    private static void fields(List<Change> out, ClassFacts was, ClassFacts is, FactsIndex after) {
        Set<String> hadNames = new HashSet<>();
        for (FieldFacts f : was.fields()) hadNames.add(f.name());
        List<FieldFacts> sorted = new ArrayList<>(was.fields());
        sorted.sort(Comparator.comparing(FieldFacts::name));
        for (FieldFacts f : sorted) {
            if (!exposed(f.access())) continue;
            FieldFacts found = findField(after, is.name(), f);
            if (found != null && found.desc().equals(f.desc()) && isStatic(found.access()) == isStatic(f.access()))
                continue;
            if (found != null) {
                out.add(new Change(
                        Kind.SIGNATURE_CHANGED,
                        was.name(),
                        f.name(),
                        true,
                        List.of(type(found.desc()) + " " + f.name())));
                continue;
            }
            List<String> renamed = new ArrayList<>();
            for (FieldFacts n : is.fields())
                if (exposed(n.access()) && n.desc().equals(f.desc()) && !hadNames.contains(n.name()))
                    renamed.add(n.name());
            out.add(
                    renamed.size() == 1
                            ? new Change(Kind.RENAMED, was.name(), f.name(), true, renamed)
                            : new Change(Kind.REMOVED, was.name(), f.name(), true, List.of()));
        }
    }

    /**
     * The exposed method {@code name desc} {@code owner} offers in {@code index}: its own, or one a
     * supertype the index holds declares (a constructor only its own). A supertype outside the index
     * may declare anything, so it answers {@code wanted} itself unless it is a JDK type, which
     * answers only {@code Object}'s methods.
     */
    private static @Nullable MethodFacts findMethod(
            FactsIndex index, String owner, MethodFacts wanted, boolean constructor) {
        String name = wanted.name();
        String desc = wanted.desc();
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String cls = todo.pop();
            if (!seen.add(cls)) continue;
            ClassFacts c = index.classes().get(cls);
            if (c == null) {
                if (jdk(cls) && !OBJECT_METHODS.contains(name + desc)) continue;
                return wanted;
            }
            for (MethodFacts m : c.methods()) {
                if (m.name().equals(name) && m.desc().equals(desc) && exposed(m.access())) return m;
            }
            if (constructor) return null;
            if (c.superName() != null) todo.add(c.superName());
            todo.addAll(c.interfaces());
        }
        return null;
    }

    /** As {@link #findMethod} for a field by name. */
    private static @Nullable FieldFacts findField(FactsIndex index, String owner, FieldFacts wanted) {
        String name = wanted.name();
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String cls = todo.pop();
            if (!seen.add(cls)) continue;
            ClassFacts c = index.classes().get(cls);
            if (c == null) {
                if (jdk(cls)) continue;
                return wanted;
            }
            for (FieldFacts f : c.fields()) if (f.name().equals(name) && exposed(f.access())) return f;
            if (c.superName() != null) todo.add(c.superName());
            todo.addAll(c.interfaces());
        }
        return null;
    }

    // ---- the join ----------------------------------------------------------------

    /**
     * Where {@code project} uses each change: a call or field access whose owner (or the library
     * class above the project's own owner) reaches a changed member through {@code before}'s
     * hierarchy, and, for a class gone, every call, access and supertype naming it. Each place once
     * per using member, ordered by file and line.
     */
    public static Map<Change, List<Site>> join(List<Change> changes, FactsIndex before, List<Classes> project) {
        Map<String, Change> byKey = new HashMap<>();
        for (Change c : changes) byKey.put(c.key(), c);
        Map<String, ClassFacts> own = new HashMap<>();
        for (Classes set : project) own.putAll(set.facts().classes());
        Map<Change, List<Site>> out = new LinkedHashMap<>();
        for (Classes set : project) {
            for (ClassFacts c : set.facts().classList()) {
                String file = set.file().apply(c);
                if (file == null) continue;
                String type = simpleDisplay(c.name());
                List<String> supers = new ArrayList<>(c.interfaces());
                if (c.superName() != null) supers.add(c.superName());
                for (String sup : supers) {
                    Change gone = byKey.get(sup + "#");
                    if (gone != null) add(out, gone, new Site(file, firstLine(c), type));
                }
                for (MethodFacts m : c.methods()) {
                    String from = type + "." + (m.name().equals("<init>") ? simple(c.name()) : m.name());
                    for (CallSite s : m.calls()) {
                        Change hit = changed(byKey, before, own, s.owner(), s.name() + s.desc(), false);
                        if (hit != null) add(out, hit, new Site(file, s.line(), from));
                    }
                    for (FieldRef r : m.fieldRefs()) {
                        Change hit = changed(byKey, before, own, r.owner(), r.name(), true);
                        if (hit != null) add(out, hit, new Site(file, r.line(), from));
                    }
                }
            }
        }
        for (List<Site> sites : out.values())
            sites.sort(Comparator.comparing(Site::file)
                    .thenComparingInt(Site::line)
                    .thenComparing(Site::from));
        return out;
    }

    private static void add(Map<Change, List<Site>> out, Change c, Site s) {
        List<Site> sites = out.computeIfAbsent(c, k -> new ArrayList<>());
        if (!sites.contains(s)) sites.add(s);
    }

    /**
     * The change a use of {@code member} on {@code owner} meets: the owner's class gone, or the
     * member gone from the first class of {@code before} on the owner's supertype path that declares
     * it. A project class is followed up to the library class it extends.
     */
    private static @Nullable Change changed(
            Map<String, Change> byKey,
            FactsIndex before,
            Map<String, ClassFacts> own,
            String owner,
            String member,
            boolean field) {
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String cls = todo.pop();
            if (!seen.add(cls)) continue;
            ClassFacts lib = before.classes().get(cls);
            ClassFacts c = lib != null ? lib : own.get(cls);
            if (c == null) continue;
            if (lib != null) {
                Change gone = byKey.get(cls + "#");
                if (gone != null && cls.equals(owner)) return gone;
                if (declares(lib, member, field)) return byKey.get(cls + "#" + member);
            } else if (declares(c, member, field)) {
                return null;
            }
            if (c.superName() != null) todo.add(c.superName());
            todo.addAll(c.interfaces());
        }
        return null;
    }

    private static boolean declares(ClassFacts c, String member, boolean field) {
        if (field) {
            for (FieldFacts f : c.fields()) if (f.name().equals(member)) return true;
            return false;
        }
        for (MethodFacts m : c.methods()) if (m.member().equals(member)) return true;
        return false;
    }

    // ---- helpers -----------------------------------------------------------------

    private static final int PUBLIC = 0x0001;
    private static final int PROTECTED = 0x0004;
    private static final int STATIC = 0x0008;
    private static final int BRIDGE = 0x0040;
    private static final int SYNTHETIC = 0x1000;

    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString()Ljava/lang/String;",
            "hashCode()I",
            "equals(Ljava/lang/Object;)Z",
            "getClass()Ljava/lang/Class;",
            "clone()Ljava/lang/Object;",
            "finalize()V",
            "notify()V",
            "notifyAll()V",
            "wait()V",
            "wait(J)V",
            "wait(JI)V");

    /** Public or protected classes, not synthetic, anonymous or {@code package-info}, by internal name. */
    private static Map<String, ClassFacts> surface(FactsIndex index) {
        Map<String, ClassFacts> out = new HashMap<>();
        for (ClassFacts c : index.classList()) {
            if (c.isPackageInfo() || c.hasFlag(SYNTHETIC) || !exposed(c.access())) continue;
            if (c.name().matches(".*\\$\\d+.*")) continue;
            out.put(c.name(), c);
        }
        return out;
    }

    private static boolean exposed(int access) {
        return (access & (PUBLIC | PROTECTED)) != 0 && (access & SYNTHETIC) == 0;
    }

    private static boolean isStatic(int access) {
        return (access & STATIC) != 0;
    }

    private static boolean jdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/");
    }

    private static String simple(String internalName) {
        return internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1);
    }

    private static String simpleDisplay(String internalName) {
        String outer = internalName.substring(internalName.lastIndexOf('/') + 1);
        return outer.replace('$', '.');
    }

    private static int firstLine(ClassFacts c) {
        int line = 0;
        for (MethodFacts m : c.methods())
            if (m.firstLine() > 0 && (line == 0 || m.firstLine() < line)) line = m.firstLine();
        return line;
    }

    /** {@code name(Type, Type)} for {@code name(desc)}, a constructor as {@code <init>(…)}. */
    static String method(String member) {
        int paren = member.indexOf('(');
        String name = member.substring(0, paren);
        try {
            MethodTypeDesc d = MethodTypeDesc.ofDescriptor(member.substring(paren));
            List<String> params = new ArrayList<>();
            for (ClassDesc p : d.parameterList()) params.add(p.displayName());
            String ret = name.equals("<init>") ? "" : ": " + d.returnType().displayName();
            return name + "(" + String.join(", ", params) + ")" + ret;
        } catch (IllegalArgumentException odd) {
            return member;
        }
    }

    private static String type(String desc) {
        try {
            return ClassDesc.ofDescriptor(desc).displayName();
        } catch (IllegalArgumentException odd) {
            return desc;
        }
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
