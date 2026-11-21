// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The versions an earlier graph fixed on this graph's classpath, keyed {@code group:artifact →
 * version}: the test graph's classpath carries main's, so every test-graph edge onto one of these
 * modules takes main's version. An edge that version does not satisfy is remembered as an override
 * so the lock can say so. Empty for the main graph.
 */
final class GoverningVersions {

    /** What a conflict names behind an edge held at a governing version: the main graph's own pins. */
    static final String ORIGIN = "[dependencies]";

    private Map<String, String> pins = Map.of();

    /** Overrides recorded across every scope solve; one entry per distinct (parent, module). */
    private final Set<Override> overrides = ConcurrentHashMap.newKeySet();

    /**
     * A transitive's constraint on a governed module that the governing version does not satisfy.
     *
     * @param parent the {@code group:artifact version} whose POM declared the edge
     * @param module the governed {@code group:artifact}
     * @param asked the selector the POM wrote
     * @param pin the governing version
     */
    record Override(String parent, String module, String asked, String pin) {
        /** What the parent asked, as one clause of the module's line. */
        String asking() {
            return parent + " asked for " + asked;
        }
    }

    /** One line per governed module: its version, then every dependency it overrode and what each asked for. */
    static String render(String module, String pin, List<Override> overrides) {
        StringBuilder out = new StringBuilder(module).append(' ').append(pin).append(": tests use the main version; ");
        if (overrides.size() == 1) {
            out.append(overrides.getFirst().asking());
        } else {
            out.append(overrides.size())
                    .append(" dependencies asked for others: ")
                    .append(overrides.stream().map(Override::asking).collect(Collectors.joining(", ")));
        }
        return out.toString();
    }

    /** The governing versions of the graph about to be solved; empty for the main graph. */
    void set(Map<String, String> gaToVersion) {
        this.pins = Map.copyOf(Objects.requireNonNull(gaToVersion, "gaToVersion"));
    }

    /**
     * The constraint an edge from {@code parentPkg@parentVersion} onto {@code depPkg} carries into
     * the solve: the governing version when one governs that module, else {@code own}.
     *
     * @param declared the plain version the POM wrote, or {@code null} for a range
     */
    VersionSet constraintFor(
            String parentPkg, String parentVersion, String depPkg, VersionSet own, @Nullable String declared) {
        if (pins.isEmpty()) return own;
        String module = PackageId.parse(depPkg).ga();
        String pin = pins.get(module);
        if (pin == null) return own;
        if (!own.contains(pin)) {
            String asked = declared != null ? declared : own.toString();
            overrides.add(new Override(PackageId.parse(parentPkg).ga() + " " + parentVersion, module, asked, pin));
        }
        return VersionSet.exact(pin);
    }

    /**
     * {@link #ORIGIN} when {@code constraint} is the governing version of {@code depPkg}'s module,
     * which is what {@link #constraintFor} made of every edge onto it; otherwise {@code null}.
     */
    @Nullable
    String pinnedBy(String depPkg, VersionSet constraint) {
        if (pins.isEmpty()) return null;
        String pin = pins.get(PackageId.parse(depPkg).ga());
        return pin != null && PinOrigins.sameVersions(constraint, VersionSet.exact(pin)) ? ORIGIN : null;
    }

    /** Every override recorded so far, one line per governed module, sorted by module. */
    List<String> renderedOverrides() {
        Map<String, List<Override>> byModule = new TreeMap<>();
        for (Override o : overrides) {
            byModule.computeIfAbsent(o.module(), k -> new ArrayList<>()).add(o);
        }
        List<String> out = new ArrayList<>(byModule.size());
        for (List<Override> group : byModule.values()) {
            group.sort(Comparator.comparing(Override::parent));
            out.add(render(group.getFirst().module(), group.getFirst().pin(), group));
        }
        return out;
    }
}
