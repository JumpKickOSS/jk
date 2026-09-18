// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maven Shade's and Gradle Shadow's relocations as jk {@code relocate} rules. A jk rule moves a
 * whole package, matched by segments, first rule winning; Shade's relocator moves every class
 * whose name starts with its pattern, narrowed by {@code includes} and {@code excludes} written
 * as Ant paths ({@code a.b.**} the subtree, {@code a.b.*} the classes directly in {@code a.b}),
 * and again the first relocator that accepts a class decides.
 *
 * <p>A relocation imports as a rule where the two agree on every class: a plain one is the rule
 * itself; one narrowed by includes contributes a rule per {@code a.b.**} include; an exclude is
 * redundant when another relocation lands the excluded classes where the rule would have (the
 * Shade idiom of excluding a package only to relocate it by a second entry to the same name); an
 * include that names a class or a package's direct classes needs no rule when the rules already
 * move them where Shade does. What the rules cannot express is reported by label, and the
 * classes it named are bundled under their own names.
 */
public final class RelocationRules {

    private RelocationRules() {}

    /**
     * One Shade {@code <relocation>} or Shadow {@code relocate}: {@code shaded} is null when the
     * declaration omits it; a path-spelled pattern ({@code org/apache/lucene}) is the same
     * relocation as its dotted twin unless {@code rawString} makes it a literal string rewrite.
     */
    public record Relocation(
            String pattern, @Nullable String shaded, boolean rawString, List<String> includes, List<String> excludes) {

        public Relocation {
            Objects.requireNonNull(pattern, "pattern");
            if (!rawString) {
                pattern = dotted(pattern);
                shaded = shaded == null ? null : dotted(shaded);
            }
            includes = includes == null
                    ? List.of()
                    : includes.stream().map(RelocationRules::dotted).toList();
            excludes = excludes == null
                    ? List.of()
                    : excludes.stream().map(RelocationRules::dotted).toList();
        }

        /** A whole package moved with nothing narrowing it. */
        public Relocation(String pattern, @Nullable String shaded) {
            this(pattern, shaded, false, List.of(), List.of());
        }

        public String label() {
            return pattern + (shaded == null ? "" : " → " + shaded);
        }

        /** Shade's own answer: whether this relocator accepts {@code name}, a dotted class name. */
        boolean accepts(String name) {
            if (rawString || shaded == null || !name.startsWith(pattern)) return false;
            if (!includes.isEmpty() && includes.stream().noneMatch(inc -> matches(inc, name))) return false;
            return excludes.stream().noneMatch(exc -> matches(exc, name));
        }

        /** Where Shade puts {@code name} once this relocator accepted it. */
        String target(String name) {
            return Objects.requireNonNull(shaded) + name.substring(pattern.length());
        }
    }

    /**
     * @param rules the {@code relocate} table, source package to shaded package in declaration order
     * @param unmapped the labels of what the rules do not express, each with the construct that
     *     kept it out, for the import row
     */
    public record Mapped(Map<String, String> rules, List<String> unmapped) {}

    /** {@code relocations} as the rules that agree with them and the labels of what stays out. */
    public static Mapped map(List<Relocation> relocations) {
        Map<String, String> rules = new LinkedHashMap<>();
        Map<String, Relocation> owner = new LinkedHashMap<>();
        for (Relocation r : relocations) {
            if (r.rawString() || r.shaded() == null) continue;
            if (r.includes().isEmpty()) {
                put(rules, owner, r.pattern(), r.shaded(), r);
                continue;
            }
            for (String include : r.includes()) {
                String pkg = wholePackage(include);
                if (pkg != null && (pkg.equals(r.pattern()) || pkg.startsWith(r.pattern()))) {
                    put(rules, owner, pkg, r.target(pkg), r);
                }
            }
        }
        List<String> unmapped = new ArrayList<>();
        dropUncoveredExcludes(relocations, rules, owner, unmapped);
        for (Relocation r : relocations) {
            if (r.rawString()) {
                if (r.shaded() == null || !rules.containsValue(dotted(r.shaded()))) {
                    unmapped.add(r.label() + " (rawString)");
                }
                continue;
            }
            if (r.shaded() == null) {
                unmapped.add(r.label() + " (no shadedPattern)");
                continue;
            }
            for (String include : r.includes()) {
                if (wholePackage(include) != null) continue;
                String probe = probe(include);
                if (probe == null || !shadeTarget(relocations, probe).equals(jkTarget(rules, probe))) {
                    unmapped.add(r.label() + " (includes " + include + ")");
                }
            }
        }
        return new Mapped(Collections.unmodifiableMap(new LinkedHashMap<>(rules)), List.copyOf(unmapped));
    }

    private static void put(
            Map<String, String> rules, Map<String, Relocation> owner, String from, String to, Relocation r) {
        if (rules.containsKey(from)) return;
        rules.put(from, to);
        owner.put(from, r);
    }

    /**
     * A relocation whose exclude Shade honours but the rules would move stays out, with its rules:
     * removing a rule can uncover another relocation's exclude, so the check repeats until stable.
     */
    private static void dropUncoveredExcludes(
            List<Relocation> relocations, Map<String, String> rules, Map<String, Relocation> owner, List<String> out) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Relocation r : relocations) {
                if (r.excludes().isEmpty() || !owner.containsValue(r)) continue;
                for (String exclude : r.excludes()) {
                    String probe = probe(exclude);
                    if (probe != null && shadeTarget(relocations, probe).equals(jkTarget(rules, probe))) continue;
                    rules.keySet().removeIf(from -> owner.get(from) == r);
                    owner.values().removeIf(o -> o == r);
                    out.add(r.label() + " (excludes " + exclude + ")");
                    changed = true;
                    break;
                }
            }
        }
    }

    /** Where Shade's relocators, in order, put the class {@code name}; itself when none accepts it. */
    static String shadeTarget(List<Relocation> relocations, String name) {
        for (Relocation r : relocations) {
            if (r.accepts(name)) return r.target(name);
        }
        return name;
    }

    /** Where jk's rules, first whole-segment match winning, put the class {@code name}; itself when none does. */
    static String jkTarget(Map<String, String> rules, String name) {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String from = rule.getKey();
            if (name.equals(from)) return rule.getValue();
            if (name.startsWith(from + ".")) return rule.getValue() + name.substring(from.length());
        }
        return name;
    }

    /** The package an {@code a.b.**} include or exclude spells, or null for any other shape. */
    static @Nullable String wholePackage(String pattern) {
        if (!pattern.endsWith(".**")) return null;
        String pkg = pattern.substring(0, pattern.length() - 3);
        return pkg.isEmpty() || pkg.contains("*") ? null : pkg;
    }

    /**
     * A class name the include or exclude {@code pattern} accepts, standing for every class it
     * names: one directly under the package for {@code a.b.**} and {@code a.b.*}, the class itself
     * for a literal name; null for a wildcard shape with no single package under it.
     */
    static @Nullable String probe(String pattern) {
        if (pattern.endsWith(".**") || pattern.endsWith(".*")) {
            String pkg = pattern.substring(0, pattern.lastIndexOf('.'));
            return pkg.isEmpty() || pkg.contains("*") ? null : pkg + ".Probe";
        }
        return pattern.contains("*") ? null : pattern;
    }

    /**
     * Ant-path matching over dotted names, as Shade matches includes and excludes: {@code **}
     * crosses segments, {@code *} stays within one, and {@code a.b.*} also names the package
     * {@code a.b} itself.
     */
    static boolean matches(String pattern, String name) {
        if (pattern.endsWith(".*") && name.equals(pattern.substring(0, pattern.length() - 2))) return true;
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^.]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString()).matcher(name).matches();
    }

    /** {@code org/apache/lucene} as {@code org.apache.lucene}; a dotted name is itself. */
    static String dotted(String name) {
        return name.replace('/', '.');
    }
}
