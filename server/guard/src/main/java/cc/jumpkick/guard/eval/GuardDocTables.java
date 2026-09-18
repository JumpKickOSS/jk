// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.util.List;
import java.util.Map;

/**
 * The two tables {@code docs/user/guards.md} carries as renderings of the loader's own schema: the
 * rule kinds, and every kind's keys. Rendered by the {@code guard-kinds} and {@code guard-schemas}
 * extractors through the {@code table} template, from the classes on the caller's classpath — in
 * the guard suite that is the tree being guarded, so a hint edited beside its row lands in one
 * commit whatever engine hosts the lane.
 */
public final class GuardDocTables {

    private GuardDocTables() {}

    /** The columns of the kinds table, in print order. */
    public static final List<String> KIND_COLUMNS = List.of("kind", "substrate", "lane", "summary");

    /** The columns of the keys table, in print order. */
    public static final List<String> SCHEMA_COLUMNS = List.of("kind", "key", "required", "type", "meaning");

    /** The kinds table: header, separator and one row per kind, without the markers. */
    public static List<String> kinds() {
        Extraction x = Extractors.guardKinds(new Extractors.Spec("guard-kinds", Map.of()));
        return Templates.render(new Templates.Spec("table", KIND_COLUMNS, ""), x);
    }

    /** The keys table: header, separator, every kind's keys, then the common keys, without the markers. */
    public static List<String> schemas() {
        try {
            Extraction x = Extractors.guardSchemas(new Extractors.Spec("guard-schemas", Map.of()));
            return Templates.render(new Templates.Spec("table", SCHEMA_COLUMNS, ""), x);
        } catch (ExtractorException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
