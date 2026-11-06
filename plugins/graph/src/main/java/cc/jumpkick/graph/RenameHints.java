// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.util.Collection;
import java.util.List;

/** What a refused rename says next: the type the agent most likely meant, or which modules to build. */
final class RenameHints {

    /** Unbuilt modules named in full; past this the reply gives a count. */
    private static final int SHOWN = 5;

    private static final String SUGGESTION = " Did you mean ";

    private RenameHints() {}

    /**
     * Why {@code wanted} names no method, with the compiled types of the same simple name that declare
     * {@code name}. {@code declaring} holds internal names ({@code a/b/Outer$Inner}) of every class
     * that declares a method called {@code name}.
     */
    static String noMethod(String wanted, String owner, String name, Collection<String> declaring) {
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        List<String> close = declaring.stream()
                .filter(c -> c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('$')) + 1)
                        .equals(simple))
                .map(c -> c.replace('/', '.').replace('$', '.') + "." + name)
                .sorted()
                .toList();
        String miss = "No method '" + wanted + "' in the compiled classes.";
        if (close.isEmpty()) return miss + " Pass Type.method.";
        return miss + SUGGESTION + String.join(" or ", close) + "? Pass that.";
    }

    /**
     * {@code error}, and, unless it already names the method meant, the modules with no compiled
     * classes ({@code prefix set}) that a build compiles: every set but the guard suite.
     */
    static String withUnbuilt(String error, List<String> sets) {
        List<String> unbuilt =
                sets.stream().filter(set -> !set.endsWith(" guard")).toList();
        if (unbuilt.isEmpty() || error.contains(SUGGESTION)) return error;
        String names = String.join(", ", unbuilt.subList(0, Math.min(SHOWN, unbuilt.size())));
        String more = unbuilt.size() > SHOWN ? " and " + (unbuilt.size() - SHOWN) + " more" : "";
        return error + " " + unbuilt.size() + " modules have no compiled classes (" + names + more
                + "): build them first (run kind=build, or jk build).";
    }
}
