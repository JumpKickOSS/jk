// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The facts an unsatisfiable resolve derives from, typed for a reader that is not a person: what
 * {@link Diagnostics#render} explains in sentences, as data, and {@link #toMap()} as JSON values.
 *
 * @param conclusion the sentence the explanation ends on, without color
 * @param packages the packages, {@code group:artifact}, in the final incompatibility
 * @param facts every external fact the failure derives from, each once, in the order the
 *     explanation meets them
 * @param declared the project's own dependencies the failure derives from ({@link
 *     Diagnostics#rootConstraints}): the declarations to relax or remove
 */
public record Conflict(String conclusion, List<String> packages, List<Fact> facts, List<String> declared) {

    public Conflict {
        packages = List.copyOf(packages);
        facts = List.copyOf(facts);
        declared = List.copyOf(declared);
    }

    /**
     * One external fact. {@code kind} is {@code dependency}, {@code constraint} (bounds a package
     * without requiring it), {@code no-versions}, {@code not-found}, {@code unavailable} or {@code
     * budget}.
     *
     * @param pkg the package the fact is about, {@code group:artifact}; {@code ""} for {@code budget}
     * @param versions the versions the fact names: required, allowed, asked for or skipped
     * @param from the package whose dependency this is, {@code null} for the project's own and for
     *     every kind but {@code dependency} and {@code constraint}
     * @param fromVersions the versions of {@code from} the dependency holds for
     * @param pinnedBy the BOM ({@code group:artifact:version}) or managed entry ({@code
     *     jk.toml:<handle>}) that decided {@code versions}, or {@code null}
     * @param available a sample of the versions repositories advertise, highest first
     * @param notes the source's reasons, or the budget that ran out
     */
    public record Fact(
            String kind,
            String pkg,
            String versions,
            @Nullable String from,
            @Nullable String fromVersions,
            @Nullable String pinnedBy,
            List<String> available,
            List<String> notes) {

        public Fact {
            available = List.copyOf(available);
            notes = List.copyOf(notes);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            if (!pkg.isEmpty()) m.put("package", pkg);
            if (!versions.isEmpty()) m.put("versions", versions);
            if (from != null) m.put("from", from);
            if (fromVersions != null) m.put("fromVersions", fromVersions);
            if (pinnedBy != null) m.put("pinnedBy", pinnedBy);
            if (!available.isEmpty()) m.put("available", available);
            if (!notes.isEmpty()) m.put("notes", notes);
            return m;
        }
    }

    /** The conflict {@code rootCause}, the incompatibility a failed solve ended on, derives from. */
    public static Conflict of(Incompatibility rootCause) {
        return Diagnostics.conflict(rootCause);
    }

    /** JSON-shaped form: strings, lists and maps only; absent fields are left out. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conclusion", conclusion);
        m.put("packages", packages);
        List<Map<String, Object>> rows = new ArrayList<>(facts.size());
        for (Fact f : facts) rows.add(f.toMap());
        m.put("facts", rows);
        m.put("declared", declared);
        return m;
    }
}
