// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The parsed contents of a single Maven POM. Property substitution within the POM's own scope
 * ({@code ${project.*}} and {@code <properties>}) has already been applied; cross-POM concerns
 * (parent inheritance, BOM imports, external properties) are the resolver's job.
 *
 * <p>{@code inheritableManaged} is the same {@code <dependencyManagement>} table with the implicit
 * {@code project.*}, {@code pom.*} and {@code parent.*} properties left as written: a child that
 * inherits the table values them in its own context, as Maven does, so a parent's {@code
 * ${project.parent.version}} is the child's parent's version, not this POM's parent's.
 */
public record Pom(
        @Nullable String groupId,
        String artifactId,
        @Nullable String version,
        String packaging,
        @Nullable Parent parent,
        Map<String, String> properties,
        List<Dep> dependencies,
        List<Dep> managedDependencies,
        @Nullable Relocation relocation,
        List<Repository> repositories,
        List<Dep> inheritableManaged) {

    /**
     * A POM with no {@code <distributionManagement>} redirect and no {@code <repositories>}, whose
     * managed table spells no implicit property.
     */
    public Pom(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            @Nullable Parent parent,
            Map<String, String> properties,
            List<Dep> dependencies,
            List<Dep> managedDependencies) {
        this(
                groupId,
                artifactId,
                version,
                packaging,
                parent,
                properties,
                dependencies,
                managedDependencies,
                null,
                List.of(),
                managedDependencies);
    }

    public Pom {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(managedDependencies, "managedDependencies");
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
        repositories = List.copyOf(repositories);
        inheritableManaged = List.copyOf(inheritableManaged);
    }

    /**
     * A {@code <repository>} the POM declares for its own dependencies, at the top level or in a
     * profile Maven activates without a command line ({@code activeByDefault}, or a {@code
     * <property><name>!x</name>} activation). Maven Central is never listed: every group has it.
     * {@code declaredBy} is the {@code g:a:v} of the POM that wrote it, for the lock's note. {@code
     * releases} and {@code snapshots} are the {@code <releases><enabled>} and {@code
     * <snapshots><enabled>} policies, both on when the POM leaves them unsaid.
     */
    public record Repository(String id, String url, String declaredBy, boolean releases, boolean snapshots) {
        public Repository {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(declaredBy, "declaredBy");
        }

        /** A repository with Maven's default policy: releases and snapshots both enabled. */
        public Repository(String id, String url, String declaredBy) {
            this(id, url, declaredBy, true, true);
        }
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
    public record Relocation(
            @Nullable String groupId,
            @Nullable String artifactId,
            @Nullable String version,
            @Nullable String message) {

        /** Resolve against the coordinate that was asked for, filling in whatever was omitted. */
        public Coordinate applyTo(Coordinate from) {
            return new Coordinate(
                    groupId == null || groupId.isBlank() ? from.group() : groupId,
                    artifactId == null || artifactId.isBlank() ? from.artifact() : artifactId,
                    version == null || version.isBlank() ? from.version() : version,
                    from.classifier(),
                    from.type());
        }

        /** True when this redirect points somewhere other than {@code from}. */
        public boolean redirects(Coordinate from) {
            Coordinate to = applyTo(from);
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
            @Nullable String version,
            @Nullable String scope,
            boolean optional,
            @Nullable String classifier,
            @Nullable String type,
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
