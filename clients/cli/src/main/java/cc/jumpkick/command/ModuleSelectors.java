// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.util.ArrayList;
import java.util.List;

/** {@code -m}/{@code --affected-since}/{@code --affected} tokens forwarded to the engine. */
final class ModuleSelectors {

    /** Engine token for {@code --affected} (WIP). Not {@code affected:<ref>} — a branch named wip must stay a ref. */
    static final String WIP_TOKEN = "affected-wip";

    static final String BOTH_MESSAGE = "use --affected (WIP) or --affected-since=<ref>, not both";

    private ModuleSelectors() {}

    static List<String> tokens(String modulesSpec, String affectedSince) {
        return tokens(modulesSpec, affectedSince, false);
    }

    static List<String> tokens(String modulesSpec, String affectedSince, boolean affectedWip) {
        List<String> selectors = new ArrayList<>();
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            for (String t : modulesSpec.split(",")) {
                if (!t.isBlank()) selectors.add(t.trim());
            }
        }
        if (affectedWip) {
            selectors.add(WIP_TOKEN);
        } else if (affectedSince != null && !affectedSince.isBlank()) {
            selectors.add("affected:" + affectedSince);
        }
        return List.copyOf(selectors);
    }

    static boolean bothSelectors(boolean affectedWip, String affectedSince) {
        return affectedWip && affectedSince != null && !affectedSince.isBlank();
    }

    /**
     * True when any module selector is active. Every build-family command guards its selection
     * resolution with this — a hand-rolled disjunction is how {@code jk native --affected} shipped
     * accepting the flag and ignoring it.
     */
    static boolean anySelector(String modulesSpec, String affectedSince, boolean affectedWip) {
        return affectedWip
                || (modulesSpec != null && !modulesSpec.isBlank())
                || (affectedSince != null && !affectedSince.isBlank());
    }
}
