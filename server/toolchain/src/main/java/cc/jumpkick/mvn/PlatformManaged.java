// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependencies whose version an imported platform supplied, written as that platform's. */
final class PlatformManaged {

    private PlatformManaged() {}

    /**
     * {@code build} with each dependency on a module of {@code platformSupplied} left to the
     * platform: the {@code [platform-dependencies]} entry that supplied the version under Maven
     * keeps owning it under jk, as {@code spring-boot-starter-web = "managed"}.
     */
    static JkBuild apply(JkBuild build, Set<String> platformSupplied) {
        if (platformSupplied.isEmpty()) return build;
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Map.Entry<Scope, List<Dependency>> e :
                build.dependencies().byScope().entrySet()) {
            List<Dependency> deps = new ArrayList<>(e.getValue().size());
            for (Dependency d : e.getValue()) {
                boolean supplied = e.getKey() != Scope.PLATFORM
                        && e.getKey() != Scope.MANAGED
                        && platformSupplied.contains(d.module())
                        && d.version() instanceof VersionSelector.Exact;
                deps.add(supplied ? d.asPlatformManaged() : d);
            }
            byScope.put(e.getKey(), deps);
        }
        return build.withDependencies(new JkBuild.Dependencies(byScope));
    }
}
