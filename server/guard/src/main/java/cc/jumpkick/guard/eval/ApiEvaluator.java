// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.JarFacts;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.tomlj.TomlTable;

/**
 * {@code api}: a module's public API compared with a released one, in japicmp's change codes.
 * {@code against} is a jar path, or a {@code g:a:v} the lock pins (read from the content store the
 * lock's checksum names — offline once locked); {@code previous-release} needs the resolver and is
 * {@code not-evaluated} until it does. Public and protected classes and members are the surface,
 * narrowed by {@code packages}; {@code codes} names changes to ignore. Every change is a site
 * {@code CODE signature}, so {@code breaking = "baseline"} accepts a change with a reason through
 * {@code jk guard freeze}, and {@code "forbid"} keeps it red. Both sides empty is {@code blind}.
 */
final class ApiEvaluator implements Evaluator {

    static final List<String> CODES = List.of(
            "CLASS_REMOVED",
            "CLASS_NOW_FINAL",
            "CLASS_NOW_ABSTRACT",
            "CLASS_LESS_ACCESSIBLE",
            "METHOD_REMOVED",
            "METHOD_RETURN_TYPE_CHANGED",
            "METHOD_NOW_FINAL",
            "METHOD_NOW_STATIC",
            "METHOD_NO_LONGER_STATIC",
            "METHOD_LESS_ACCESSIBLE",
            "INTERFACE_ADDED_METHOD",
            "FIELD_REMOVED",
            "FIELD_TYPE_CHANGED",
            "FIELD_NOW_FINAL",
            "FIELD_LESS_ACCESSIBLE");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        String against = String.valueOf(t.getString("against"));
        List<String> packages = ForbidEvaluator.strings(t, "packages");
        Set<String> ignore = new TreeSet<>(ForbidEvaluator.strings(t, "codes"));
        for (String c : ignore)
            if (!CODES.contains(c))
                return Evaluation.failed("unknown change code `" + c + "`; codes are " + String.join(", ", CODES));

        // The module compared: the rule's scope names it; a workspace-wide api rule is a mistake.
        List<String> modules = new ArrayList<>();
        for (Path m : ctx.modules()) {
            String rel = relModule(ctx.root(), m);
            if (rule.applies(rel) && !rel.isEmpty()) modules.add(rel);
        }
        if (ctx.moduleDir() != null) modules = List.of(ctx.module());
        if (modules.size() != 1) {
            return Evaluation.failed("an api rule compares one module: scope it (`scope = [\"shared/jk-api\"]`), "
                    + (modules.isEmpty() ? "none matched" : modules.size() + " matched"));
        }
        String module = modules.get(0);

        Path before;
        try {
            before = locate(against, ctx.root(), JkDirs.store());
        } catch (IllegalArgumentException e) {
            return Evaluation.notEvaluated(
                    "against = " + against + " needs the lock, which did not read: " + e.getMessage());
        }
        if (before == null) {
            if (against.equals("previous-release")) {
                return Evaluation.notEvaluated(
                        "against = previous-release needs the resolver to find the latest published " + module
                                + "; pin the coordinate (against = \"g:a:v\") in the lock for now");
            }
            if (against.equals("baseline")) {
                return Evaluation.unsupported(
                        "against = baseline (a recorded API surface) has not landed; compare with a jar or a locked g:a:v");
            }
            return Evaluation.notEvaluated("against = " + against
                    + " is neither a jar on disk nor a coordinate the lock pins and the store holds");
        }
        FactsIndex old = JarFacts.of(
                before,
                ctx.root().resolve(BuildLayout.TARGET).resolve("jk-guards").resolve("api"));
        FactsIndex now = ctx.moduleDir() != null
                ? ctx.facts()
                : WorkspaceFacts.merged(ctx.root(), List.of(ctx.root().resolve(module)));

        Map<String, ClassFacts> oldApi = surface(old, packages);
        Map<String, ClassFacts> newApi = surface(now, packages);
        Map<String, Long> population = Map.of("before", (long) oldApi.size(), "after", (long) newApi.size());
        if (oldApi.isEmpty() && newApi.isEmpty())
            return new Evaluation(
                    Outcome.BLIND,
                    population,
                    List.of(),
                    "no public class on either side" + (packages.isEmpty() ? "" : " in " + packages));

        List<Observation> sites = new ArrayList<>();
        String file = module + "/" + BuildLayout.TARGET;
        for (var e : oldApi.entrySet()) {
            ClassFacts was = e.getValue();
            ClassFacts is = newApi.get(e.getKey());
            String cls = was.binaryName();
            if (is == null) {
                change(sites, ignore, "CLASS_REMOVED", cls, cls + " is gone from the public API");
                continue;
            }
            if (!was.hasFlag(Opcodes.ACC_FINAL) && is.hasFlag(Opcodes.ACC_FINAL) && !was.hasFlag(Opcodes.ACC_ENUM))
                change(sites, ignore, "CLASS_NOW_FINAL", cls, cls + " became final");
            if (!was.hasFlag(Opcodes.ACC_ABSTRACT)
                    && is.hasFlag(Opcodes.ACC_ABSTRACT)
                    && !is.hasFlag(Opcodes.ACC_INTERFACE))
                change(sites, ignore, "CLASS_NOW_ABSTRACT", cls, cls + " became abstract");
            if (was.hasFlag(Opcodes.ACC_PUBLIC) && !is.hasFlag(Opcodes.ACC_PUBLIC))
                change(sites, ignore, "CLASS_LESS_ACCESSIBLE", cls, cls + " is no longer public");
            Map<String, MethodFacts> newMethods = new HashMap<>();
            for (MethodFacts m : is.methods()) if (exposed(m.access())) newMethods.put(m.name() + params(m.desc()), m);
            for (MethodFacts m : was.methods()) {
                if (!exposed(m.access()) || m.name().equals("<clinit>")) continue;
                String sig = cls + "#" + m.name() + params(m.desc());
                MethodFacts n = newMethods.get(m.name() + params(m.desc()));
                if (n == null) {
                    change(sites, ignore, "METHOD_REMOVED", sig, sig + " is gone");
                    continue;
                }
                if (!returnOf(m.desc()).equals(returnOf(n.desc())))
                    change(
                            sites,
                            ignore,
                            "METHOD_RETURN_TYPE_CHANGED",
                            sig,
                            sig + " now returns " + returnOf(n.desc()) + ", was " + returnOf(m.desc()));
                if (!has(m, Opcodes.ACC_FINAL) && has(n, Opcodes.ACC_FINAL))
                    change(sites, ignore, "METHOD_NOW_FINAL", sig, sig + " became final");
                if (!has(m, Opcodes.ACC_STATIC) && has(n, Opcodes.ACC_STATIC))
                    change(sites, ignore, "METHOD_NOW_STATIC", sig, sig + " became static");
                if (has(m, Opcodes.ACC_STATIC) && !has(n, Opcodes.ACC_STATIC))
                    change(sites, ignore, "METHOD_NO_LONGER_STATIC", sig, sig + " is no longer static");
                if (has(m, Opcodes.ACC_PUBLIC) && !has(n, Opcodes.ACC_PUBLIC))
                    change(sites, ignore, "METHOD_LESS_ACCESSIBLE", sig, sig + " is no longer public");
            }
            if (is.hasFlag(Opcodes.ACC_INTERFACE)) {
                Set<String> hadMethods = new TreeSet<>();
                for (MethodFacts m : was.methods()) hadMethods.add(m.name() + params(m.desc()));
                for (MethodFacts n : is.methods()) {
                    if (!exposed(n.access()) || has(n, Opcodes.ACC_STATIC) || !has(n, Opcodes.ACC_ABSTRACT)) continue;
                    String key = n.name() + params(n.desc());
                    if (!hadMethods.contains(key))
                        change(
                                sites,
                                ignore,
                                "INTERFACE_ADDED_METHOD",
                                cls + "#" + key,
                                cls + "#" + key + " is a new abstract method every implementor must add");
                }
            }
            Map<String, FieldFacts> newFields = new HashMap<>();
            for (FieldFacts f : is.fields()) if (exposed(f.access())) newFields.put(f.name(), f);
            for (FieldFacts f : was.fields()) {
                if (!exposed(f.access())) continue;
                String sig = cls + "." + f.name();
                FieldFacts n = newFields.get(f.name());
                if (n == null) {
                    change(sites, ignore, "FIELD_REMOVED", sig, sig + " is gone");
                    continue;
                }
                if (!f.desc().equals(n.desc())) change(sites, ignore, "FIELD_TYPE_CHANGED", sig, sig + " changed type");
                if ((f.access() & Opcodes.ACC_FINAL) == 0 && (n.access() & Opcodes.ACC_FINAL) != 0)
                    change(sites, ignore, "FIELD_NOW_FINAL", sig, sig + " became final");
                if ((f.access() & Opcodes.ACC_PUBLIC) != 0 && (n.access() & Opcodes.ACC_PUBLIC) == 0)
                    change(sites, ignore, "FIELD_LESS_ACCESSIBLE", sig, sig + " is no longer public");
            }
        }
        List<Observation> located = new ArrayList<>();
        for (Observation o : sites) located.add(Observation.site(o.key(), file, 0, o.detail()));
        return Evaluation.of(population, located);
    }

    /** {@code breaking = "baseline"} lets a change be frozen with a reason; the default {@code forbid} never consults the baseline. */
    static boolean acceptsBaseline(Rule rule) {
        return "baseline".equals(rule.table().getString("breaking"));
    }

    private static void change(List<Observation> sites, Set<String> ignore, String code, String sig, String detail) {
        if (ignore.contains(code)) return;
        sites.add(Observation.site(code + " " + sig, null, 0, code + ": " + detail));
    }

    /** Public or protected top-level and nested classes (not synthetic, not package-info), by internal name. */
    static Map<String, ClassFacts> surface(FactsIndex index, List<String> packages) {
        Map<String, ClassFacts> out = new HashMap<>();
        for (ClassFacts c : index.classList()) {
            if (c.isPackageInfo() || c.hasFlag(Opcodes.ACC_SYNTHETIC)) continue;
            if (!c.hasFlag(Opcodes.ACC_PUBLIC) && !c.hasFlag(Opcodes.ACC_PROTECTED)) continue;
            if (c.binaryName().contains("$") && c.binaryName().matches(".*\\$\\d+.*")) continue; // anonymous
            if (!packages.isEmpty()) {
                boolean in = false;
                for (String p : packages) if (ClassPredicates.packageMatches(p, c.packageName())) in = true;
                if (!in) continue;
            }
            out.put(c.name(), c);
        }
        return out;
    }

    private static boolean exposed(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0;
    }

    private static boolean has(MethodFacts m, int flag) {
        return (m.access() & flag) != 0;
    }

    static String params(String desc) {
        return desc.substring(0, desc.indexOf(')') + 1);
    }

    static String returnOf(String desc) {
        return desc.substring(desc.indexOf(')') + 1);
    }

    /** A jar on disk, or a locked {@code g:a:v} in the content store; {@code null} when neither. */
    static @Nullable Path locate(String against, Path root, Path store) throws IOException {
        if (against.endsWith(".jar")) {
            Path p = root.resolve(against);
            return Files.isRegularFile(p) ? p : null;
        }
        String[] parts = against.split(":");
        if (parts.length != 3) return null;
        Path lock = LockPaths.lockFile(root);
        if (!Files.isRegularFile(lock)) return null;
        Lockfile lf = LockfileReader.read(lock);
        for (Lockfile.Artifact a : lf.artifacts()) {
            if (!a.version().equals(parts[2])) continue;
            String[] name = a.name().split(":", -1);
            if (name.length < 2 || !name[0].equals(parts[0]) || !name[1].equals(parts[1])) continue;
            if (name.length > 2 && !name[2].isEmpty() && !name[2].equals("jar")) continue;
            String checksum = a.checksum();
            if (checksum == null) return null;
            String hex = checksum.startsWith("sha256:") ? checksum.substring(7) : checksum;
            Path cas = store.resolve("sha256")
                    .resolve(hex.substring(0, 2))
                    .resolve(hex.substring(2, 4))
                    .resolve(hex.substring(4));
            if (Files.isRegularFile(cas)) return cas;
            Path repos = store.resolve("repos");
            String tail = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2]
                    + ".jar";
            Path[] found = new Path[1];
            if (Files.isDirectory(repos)) {
                PathUtil.forEachChild(repos, (repo, attrs) -> {
                    Path jar = repo.resolve(tail);
                    if (attrs.isDirectory() && Files.isRegularFile(jar)) found[0] = jar;
                    return found[0] == null;
                });
            }
            return found[0];
        }
        return null;
    }

    private static String relModule(Path root, Path m) {
        Path r = root.toAbsolutePath().normalize();
        Path mm = m.toAbsolutePath().normalize();
        return r.equals(mm) ? "" : r.relativize(mm).toString().replace('\\', '/');
    }
}
