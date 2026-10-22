// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.aether.artifact.DefaultArtifact;

/**
 * Which locked artifacts are roots, declared by the manifest, and which the closure reached through
 * another locked artifact's POM. Only the roots go to Quarkus as direct dependencies: handed the
 * whole closure as direct, it validates every extension on its own, including support extensions
 * such as {@code quarkus-devservices} whose deployment artifact does not depend on its runtime, and
 * a datasource application fails to augment. The lock still pins every version ({@link
 * LockedAppModel#enforce}).
 */
final class ClosureRoots {

    /** One {@code <dependency>} of a POM. */
    record PomDependency(String group, String artifact, String scope, boolean optional) {

        /** What Maven follows transitively: compile or runtime scope, not optional. */
        boolean transitive() {
            return !optional && (scope.isEmpty() || scope.equals("compile") || scope.equals("runtime"));
        }
    }

    /** A locked artifact's POM dependencies; empty when its POM cannot be read. */
    interface Poms {
        List<PomDependency> dependencies(LockedClosure.Artifact artifact);
    }

    private ClosureRoots() {}

    /** The {@code group:artifact} of every locked artifact another locked artifact's POM reaches. */
    static Set<String> reachedFromAnother(LockedClosure locked, Poms poms) {
        Set<String> inClosure = new HashSet<>();
        for (LockedClosure.Artifact a : locked.artifacts()) inClosure.add(a.group() + ":" + a.artifact());
        Set<String> reached = new HashSet<>();
        for (LockedClosure.Artifact a : locked.artifacts()) {
            if (a.workspace()) continue;
            String self = a.group() + ":" + a.artifact();
            for (PomDependency d : poms.dependencies(a)) {
                String ga = d.group() + ":" + d.artifact();
                if (d.transitive() && inClosure.contains(ga) && !ga.equals(self)) reached.add(ga);
            }
        }
        return reached;
    }

    /** The POMs as the bootstrap resolver reads them, from jk's store first. */
    static Poms of(MavenArtifactResolver mvn) {
        return a -> {
            List<PomDependency> out = new ArrayList<>();
            try {
                var descriptor =
                        mvn.resolveDescriptor(new DefaultArtifact(a.group(), a.artifact(), "", "pom", a.version()));
                for (var d : descriptor.getDependencies()) {
                    out.add(new PomDependency(
                            d.getArtifact().getGroupId(),
                            d.getArtifact().getArtifactId(),
                            d.getScope(),
                            d.isOptional()));
                }
            } catch (BootstrapMavenException e) {
                // An unreadable POM leaves everything it would have reached a root.
            }
            return out;
        };
    }
}
