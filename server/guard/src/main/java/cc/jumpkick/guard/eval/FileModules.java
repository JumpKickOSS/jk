// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The module a file belongs to, the one a file-based rule's {@code scope} is judged by: the member
 * whose directory most deeply contains it, so a pack nested under its parent module's directory is
 * its own module, and the workspace root ({@code ""}) for a file outside every member. Paths are
 * spelled as the lane's corpus spells them: from the workspace root on a root lane, from the module
 * directory on a module lane, where every file is that module's.
 */
final class FileModules {

    /** Module paths by {@link WorkspaceModel#rel}, longest first, so the first holder found is the deepest. */
    private final List<String> deepestFirst;

    /** The module lane's own module: every file the lane sees is its. */
    private final @Nullable String only;

    private FileModules(List<String> deepestFirst, @Nullable String only) {
        this.deepestFirst = deepestFirst;
        this.only = only;
    }

    /** The members under {@code root}, given as their directories. */
    static FileModules of(Path root, List<Path> modules) {
        List<String> rels = new ArrayList<>();
        for (Path m : modules) {
            String rel = WorkspaceModel.rel(root, m);
            // the root holds every file no member does; a member outside the root holds none of its files
            if (!rel.isEmpty() && !rel.startsWith("../")) rels.add(rel);
        }
        rels.sort(Comparator.comparingInt(String::length).reversed());
        return new FileModules(List.copyOf(rels), null);
    }

    /** The mapping for {@code ctx}'s corpus, built once per lane run. */
    static FileModules of(EvalContext ctx) {
        if (ctx.lane() == Lane.MODULE && ctx.moduleDir() != null) return new FileModules(List.of(), ctx.module());
        return ctx.memo(FileModules.class, c -> of(c.root(), c.modules()));
    }

    /** The module {@code rel} belongs to. */
    String moduleOf(String rel) {
        if (only != null) return only;
        for (String m : deepestFirst) {
            if (rel.startsWith(m) && (rel.length() == m.length() || rel.charAt(m.length()) == '/')) return m;
        }
        return "";
    }

    /** Whether {@code rule} judges {@code rel}: the file's module is in its scope. */
    boolean judges(Rule rule, String rel) {
        return rule.scope().isEmpty() || rule.applies(moduleOf(rel));
    }
}
