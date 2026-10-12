// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.library.LibraryCatalog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The layered library catalog reversed: {@code group:artifact} → the one short name that means it.
 * Read once per import; a GA that zero or several catalog names share has no short name (an
 * import never invents one), and a catalog that fails to load leaves every GA unnamed.
 */
final class CatalogShortNames {

    private final Map<String, String> byModule;

    private CatalogShortNames(Map<String, String> byModule) {
        this.byModule = byModule;
    }

    static CatalogShortNames layered() {
        Map<String, String> unique = new HashMap<>();
        Set<String> shared = new HashSet<>();
        try {
            LibraryCatalog catalog = LibraryCatalog.layered();
            for (String name : catalog.names()) {
                catalog.lookup(name).ifPresent(module -> {
                    String key = module.moduleKey();
                    if (shared.contains(key)) return;
                    if (unique.putIfAbsent(key, name) != null) {
                        unique.remove(key);
                        shared.add(key);
                    }
                });
            }
        } catch (RuntimeException e) {
            unique.clear();
        }
        return new CatalogShortNames(Map.copyOf(unique));
    }

    /** The short name for {@code groupArtifact}; empty when the catalog has none or more than one. */
    Optional<String> of(String groupArtifact) {
        return Optional.ofNullable(byModule.get(groupArtifact));
    }
}
