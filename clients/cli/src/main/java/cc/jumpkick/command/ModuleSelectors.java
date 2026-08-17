// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.util.ArrayList;
import java.util.List;

/** {@code -m}/{@code --affected-since} tokens forwarded to the engine (same shape as compile). */
final class ModuleSelectors {

    private ModuleSelectors() {}

    static List<String> tokens(String modulesSpec, String affectedSince) {
        List<String> selectors = new ArrayList<>();
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            for (String t : modulesSpec.split(",")) {
                if (!t.isBlank()) selectors.add(t.trim());
            }
        }
        if (affectedSince != null && !affectedSince.isBlank()) {
            selectors.add("affected:" + affectedSince);
        }
        return List.copyOf(selectors);
    }
}
