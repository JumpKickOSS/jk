// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed contents of a single Maven POM. Property substitution within the POM's own scope
 * ({@code ${project.*}} and {@code <properties>}) has already been applied; cross-POM concerns
 * (parent inheritance, BOM imports, external properties) are the resolver's job.
 */
public record Pom(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Parent parent,
        Map<String, String> properties,
        List<Dep> dependencies,
        List<Dep> managedDependencies,
        Relocation relocation) {

    /** Compatibility constructor for POMs with no {@code <distributionManagement>} redirect. */
    public Pom(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Parent parent,
            Map<String, String> properties,
            List<Dep> dependencies,
            List<Dep> managedDependencies) {
        this(groupId, artifactId, version, packaging, parent, properties, dependencies, managedDependencies, null);
    }

    public Pom {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(managedDependencies, "managedDependencies");
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
    }

    /** True when groupId or version was inherited from {@code <parent>}. */
    public boolean inheritsCoordsFromParent() {
        return parent != null && (groupId == null || version == null);
    }

    /**
     * {@code <distributionManagement><relocation>} — this coordinate has moved. Maven and Gradle
     * resolve the target in its place, so anything that does not follow it ends up with the
     * redirect stub, which has no classes and no dependencies. Any field may be absent, in which
     * case the requesting coordinate's own value carries over.
     */
    public record Relocation(String groupId, String artifactId, String version, String message) {

        /** Resolve against the coordinate that was asked for, filling in whatever was omitted. */
        public cc.jumpkick.model.Coordinate applyTo(cc.jumpkick.model.Coordinate from) {
            return new cc.jumpkick.model.Coordinate(
                    groupId == null || groupId.isBlank() ? from.group() : groupId,
                    artifactId == null || artifactId.isBlank() ? from.artifact() : artifactId,
                    version == null || version.isBlank() ? from.version() : version,
                    from.classifier(),
                    from.type());
        }

        /** True when this redirect points somewhere other than {@code from}. */
        public boolean redirects(cc.jumpkick.model.Coordinate from) {
            cc.jumpkick.model.Coordinate to = applyTo(from);
            return !to.group().equals(from.group())
                    || !to.artifact().equals(from.artifact())
                    || !Objects.equals(to.version(), from.version());
        }
    }

    public record Parent(String groupId, String artifactId, String version) {
        public Parent {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * A {@code <dependency>} entry. Maven's {@code system} scope is captured as a raw string here so
     * the parser stays lossless; higher layers reject unsupported packaging.
     */
    public record Dep(
            String groupId,
            String artifactId,
            String version,
            String scope,
            boolean optional,
            String classifier,
            String type,
            List<Exclusion> exclusions) {

        public Dep {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(exclusions, "exclusions");
            exclusions = List.copyOf(exclusions);
        }

        /** Maven coordinate without classifier or type. */
        public String module() {
            return groupId + ":" + artifactId;
        }

        public record Exclusion(String groupId, String artifactId) {
            public Exclusion {
                Objects.requireNonNull(groupId, "groupId");
                Objects.requireNonNull(artifactId, "artifactId");
            }
        }
    }
}
