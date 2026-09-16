// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The graph's exact roots under {@code [resolve] pins = "nearest"}, keyed {@code group:artifact →
 * version}. Every transitive edge onto one of these modules takes the pin, as a direct
 * dependency's version does under Maven; an edge the pin does not satisfy is remembered as an
 * override so the lock can say so. Empty under the default policy, when every edge keeps its own
 * constraint.
 */
final class NearestPins {

    private Map<String, String> pins = Map.of();

    /** Overrides recorded across every scope solve; one entry per distinct (parent, module). */
    private final Set<Override> overrides = ConcurrentHashMap.newKeySet();

    /**
     * A transitive's constraint on a module the project pins that the pin does not satisfy.
     *
     * @param parent the {@code group:artifact version} whose POM declared the edge
     * @param module the pinned {@code group:artifact}
     * @param asked the selector the POM wrote
     * @param pin the version the project declared
     */
    record Override(String parent, String module, String asked, String pin) {
        String render() {
            return module + " " + pin + " is the project's pin; " + parent + " asked for " + asked
                    + " — the pin wins, as a direct dependency does under Maven";
        }
    }

    /** The exact roots of the graph about to be solved, or empty when pins are plain constraints. */
    void set(Map<String, String> gaToVersion) {
        this.pins = Map.copyOf(Objects.requireNonNull(gaToVersion, "gaToVersion"));
    }

    /**
     * The constraint an edge from {@code parentPkg@parentVersion} onto {@code depPkg} carries into
     * the solve: the nearest pin when the project has one on that module, else {@code own}.
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

    /** Every override recorded so far, rendered and sorted. */
    List<String> renderedOverrides() {
        return overrides.stream().map(Override::render).sorted().toList();
    }
}
