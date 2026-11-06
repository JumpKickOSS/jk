// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Which workspace modules can see which, as the engine's build graph says: each module's directory
 * prefix to the prefixes of the modules on its classpath or built before it. {@link #UNKNOWN} when
 * the engine sent none, so every module counts.
 */
record ModuleReach(@Nullable Map<String, Set<String>> prereqs) {

    static final ModuleReach UNKNOWN = new ModuleReach(null);

    /** {@code prefix \t prereq \t prereq …}, one per module; no rows is {@link #UNKNOWN}. */
    static ModuleReach of(List<String> rows) {
        if (rows.isEmpty()) return UNKNOWN;
        Map<String, Set<String>> out = new HashMap<>();
        for (String row : rows) {
            String[] f = row.split("\t", -1);
            Set<String> to = out.computeIfAbsent(f[0], k -> new TreeSet<>());
            for (int i = 1; i < f.length; i++) to.add(f[i]);
        }
        return new ModuleReach(out);
    }

    /**
     * {@code homes} and every module that sees one of them, directly or through another; a module of
     * {@code modules} the edges do not name counts too, since nothing says what it sees. {@code null}
     * when the edges are unknown.
     */
    @Nullable
    Set<String> reaching(Collection<String> homes, Modules modules) {
        Map<String, Set<String>> edges = prereqs;
        if (edges == null) return null;
        Map<String, Set<String>> dependents = new HashMap<>();
        edges.forEach((from, tos) -> {
            for (String to : tos)
                dependents.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
        });
        Set<String> out = new LinkedHashSet<>(homes);
        List<String> todo = new ArrayList<>(out);
        for (int i = 0; i < todo.size(); i++) {
            for (String p : dependents.getOrDefault(todo.get(i), Set.of())) {
                if (out.add(p)) todo.add(p);
            }
        }
        for (Modules.Module m : modules.list()) {
            if (!edges.containsKey(m.prefix())) out.add(m.prefix());
        }
        return out;
    }

    /**
     * Whether {@code type} (an internal name), a supertype the workspace's classes do not hold, may
     * declare a method called {@code method}: a JDK type is asked, with its own supertypes; any
     * other may, since nothing here says what it declares.
     */
    static boolean outsideMayDeclare(String type, String method) {
        Class<?> c;
        try {
            c = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError notJdk) {
            return true;
        }
        List<Class<?>> todo = new ArrayList<>(List.of(c));
        for (int i = 0; i < todo.size(); i++) {
            Class<?> t = todo.get(i);
            for (Method m : t.getDeclaredMethods()) {
                if (m.getName().equals(method) && !Modifier.isPrivate(m.getModifiers())) return true;
            }
            Class<?> parent = t.getSuperclass();
            if (parent != null) todo.add(parent);
            todo.addAll(List.of(t.getInterfaces()));
        }
        return false;
    }
}
