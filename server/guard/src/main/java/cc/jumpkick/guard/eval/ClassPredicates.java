// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.rules.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The closed set of class predicates shared by {@code classes} ({@code that} / {@code should}) and
 * {@code annotate} ({@code matching}). Each key takes a string or a list; a leading {@code !}
 * negates one value. Unknown keys are a compile error of the rule, not a silent no-op.
 *
 * <ul>
 *   <li>{@code reside-in}: a package pattern — ArchUnit's {@code ..model..} dots or a glob
 *   <li>{@code annotated-with}, {@code implement}, {@code extend}: a binary class name; the two
 *       hierarchy predicates walk the classpath and the JDK
 *   <li>{@code are} / {@code be}: {@code interface abstract enum record annotation public final
 *       nested top-level}
 *   <li>{@code named}: a glob over the simple name ({@code *Test}, {@code *Impl});
 *       {@code name-matching}: a regex over the binary name; {@code simple-name-ending-with}
 *   <li>{@code assignable-to}: either of the two hierarchy predicates
 * </ul>
 */
final class ClassPredicates {

    static final List<String> KEYS = List.of(
            "reside-in",
            "annotated-with",
            "implement",
            "extend",
            "assignable-to",
            "are",
            "be",
            "named",
            "name-matching",
            "simple-name-ending-with");

    private ClassPredicates() {}

    /** A compiled predicate, or the text of the first problem. */
    record Compiled(
            @Nullable Predicate<ClassFacts> predicate,
            @Nullable String error) {
        boolean test(ClassFacts c) {
            return predicate == null || predicate.test(c);
        }
    }

    static Compiled compile(@Nullable TomlTable table, TypeHierarchy types) {
        if (table == null) return new Compiled(null, null);
        List<Predicate<ClassFacts>> all = new ArrayList<>();
        for (Map.Entry<String, Object> e : table.toMap().entrySet()) {
            String key = e.getKey();
            if (!KEYS.contains(key)) {
                return new Compiled(null, "unknown predicate `" + key + "`; predicates are " + String.join(", ", KEYS));
            }
            for (String raw : values(e.getValue())) {
                boolean negate = raw.startsWith("!");
                String v = negate ? raw.substring(1) : raw;
                Predicate<ClassFacts> p = one(key, v, types);
                if (p == null) return new Compiled(null, "predicate " + key + " = \"" + raw + "\" is not understood");
                all.add(negate ? p.negate() : p);
            }
        }
        Predicate<ClassFacts> and = c -> {
            for (Predicate<ClassFacts> p : all) if (!p.test(c)) return false;
            return true;
        };
        return new Compiled(and, null);
    }

    private static List<String> values(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof TomlArray a) {
            for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        } else {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static @Nullable Predicate<ClassFacts> one(String key, String v, TypeHierarchy types) {
        return switch (key) {
            case "reside-in" -> c -> packageMatches(v, c.packageName());
            case "annotated-with" -> c -> c.hasAnnotation(v);
            case "implement", "extend", "assignable-to" -> {
                String target = Descriptors.internalName(v);
                yield c -> !c.name().equals(target) && types.isAssignableTo(c.name(), target);
            }
            case "name-matching" -> {
                Pattern re = Pattern.compile(v);
                yield c -> re.matcher(c.binaryName()).matches();
            }
            case "simple-name-ending-with" -> c -> simpleName(c).endsWith(v);
            case "named" -> c -> Rule.globMatches(v, simpleName(c));
            default -> shape(v);
        };
    }

    static String simpleName(ClassFacts c) {
        String name = c.binaryName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    private static @Nullable Predicate<ClassFacts> shape(String v) {
        return switch (v) {
            case "interface" -> c -> c.hasFlag(Opcodes.ACC_INTERFACE) && !c.hasFlag(Opcodes.ACC_ANNOTATION);
            case "abstract" -> c -> c.hasFlag(Opcodes.ACC_ABSTRACT) && !c.hasFlag(Opcodes.ACC_INTERFACE);
            case "enum" -> c -> c.hasFlag(Opcodes.ACC_ENUM);
            case "record" -> c -> "java/lang/Record".equals(c.superName());
            case "annotation" -> c -> c.hasFlag(Opcodes.ACC_ANNOTATION);
            case "public" -> c -> c.hasFlag(Opcodes.ACC_PUBLIC);
            case "final" -> c -> c.hasFlag(Opcodes.ACC_FINAL);
            case "nested" -> ClassFacts::isNested;
            case "top-level" -> c -> !c.isNested();
            case "static" -> c -> c.hasFlag(Opcodes.ACC_STATIC);
            case "tests" -> AnnotateEvaluator::isTestClass;
            default -> null;
        };
    }

    /**
     * {@code ..model..} matches any package with a {@code model} segment; {@code com.acme..} a tree;
     * a pattern without dots-of-two is a glob over the dotted name, and a plain name is that package.
     */
    static boolean packageMatches(String pattern, String pkg) {
        if (pattern.contains("..")) {
            StringBuilder re = new StringBuilder();
            String[] parts = pattern.split("\\.\\.", -1);
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean first = i == 0;
                boolean last = i == parts.length - 1;
                if (part.isEmpty()) {
                    if (first && last) return true;
                    continue;
                }
                if (!first) re.append("(?:.*\\.)?");
                re.append(Pattern.quote(part));
                if (!last) re.append("(?:\\..*)?");
            }
            String prefix = parts[0].isEmpty() ? "(?:.*\\.)?" : "";
            String suffix = parts[parts.length - 1].isEmpty() ? "(?:\\..*)?" : "";
            return pkg.matches(prefix + re + suffix);
        }
        if (pattern.indexOf('*') >= 0) return Rule.globMatches(pattern, pkg);
        return pkg.equals(pattern);
    }
}
