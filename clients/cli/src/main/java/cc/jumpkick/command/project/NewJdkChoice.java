// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.model.Project;
import java.util.Optional;

/**
 * Which Java level a new project from the blank scaffolder gets: a spec the user typed wins, then
 * the parent module's major, then the machine's default JDK, then the newest LTS. Everything here is
 * best-effort against the network: a catalog fetch that fails degrades to the offline constants.
 */
final class NewJdkChoice {

    private NewJdkChoice() {}

    /** The user's global default JDK identifier, or empty (best-effort — never throws). */
    static Optional<String> defaultJdk() {
        try {
            return JdkInventory.current().defaultId();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Best-effort catalog fetch: a network failure (offline, DNS, 5xx) is an empty optional. */
    static Optional<JdkCatalog> catalogQuiet() {
        try {
            return Optional.of(new JdkCatalogClient().fetch());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve an explicit {@code --jdk <spec>} into its major + {@code jk.toml} pin. Keywords
     * ({@code lts} / {@code stable} / {@code latest}) resolve to a major against the JetBrains feed
     * (falling back to the latest LTS offline) and always pin a bare major; a
     * {@code <vendor>-<major>} spec keeps its vendor; a bare major stays bare. Throws
     * {@link IllegalArgumentException} (caught by the flag path) on a point release or a spec with
     * no major.
     */
    static NewJdkPlan.Spec fromArg(String arg) {
        String a = arg.trim();
        if (JdkKeywords.isKeyword(a) && !"native".equalsIgnoreCase(a)) {
            String os = HostPlatform.currentOs();
            String arch = HostPlatform.currentArch();
            int major = catalogQuiet()
                    .flatMap(c -> JdkKeywords.resolveToMajorSpec(c, a, os, arch))
                    .map(Project::majorOf)
                    .filter(m -> m > 0)
                    .orElse(NewArgs.LATEST_LTS_MAJOR);
            return new NewJdkPlan.Spec(major, Integer.toString(major));
        }
        return NewJdkPlan.parseExplicit(a);
    }
}
