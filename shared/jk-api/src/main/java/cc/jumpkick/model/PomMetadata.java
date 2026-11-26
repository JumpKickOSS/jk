// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [publish]} table: what a published POM says about the project beyond its coordinate
 * and dependencies — the human name, the home page, the licenses, the people, the source
 * repository. Maven Central refuses a release missing any of them; a private repository reads
 * them if it cares. The same table names where a release goes: {@code repository} (a
 * {@code [repositories.<name>]} entry, or {@code central}) for the libraries, {@code release} (an
 * {@code s3://} or {@code gs://} prefix, or a forge) for the deliverables, where a {@code -SNAPSHOT}
 * goes ({@code snapshotRepository}, a declared repository) and the {@code keyFile} that signs them,
 * never a secret. {@code sign}, {@code sbom} and {@code provenance} are on unless the table turns
 * them off; {@code hold} stops a Central upload at {@code VALIDATED} for a human to release. A
 * workspace root's table applies to every member that declares none. {@code skip} keeps the module out of {@code jk publish} and {@code jk install}
 * (a test harness, an example, a site generator); it is the module's own word and is never
 * inherited from the root.
 */
public record PomMetadata(
        @Nullable String name,
        @Nullable String description,
        @Nullable String url,
        List<License> licenses,
        List<Developer> developers,
        @Nullable Scm scm,
        @Nullable String repository,
        @Nullable String release,
        @Nullable String snapshotRepository,
        boolean sign,
        @Nullable String keyFile,
        boolean skip,
        boolean sbom,
        boolean provenance,
        boolean hold) {

    public PomMetadata {
        licenses = licenses == null ? List.of() : List.copyOf(licenses);
        developers = developers == null ? List.of() : List.copyOf(developers);
    }

    /** No {@code [publish]} table: a POM with coordinate, description and dependencies only. */
    public static final PomMetadata EMPTY = new PomMetadata(
            null, null, null, List.of(), List.of(), null, null, null, null, true, null, false, true, true, false);

    public record License(String name, @Nullable String url) {
        public License {
            Objects.requireNonNull(name, "name");
        }
    }

    public record Developer(
            String id, @Nullable String name, @Nullable String email) {
        public Developer {
            Objects.requireNonNull(id, "id");
        }
    }

    public record Scm(
            @Nullable String url,
            @Nullable String connection,
            @Nullable String developerConnection) {}

    /** True when every field is unset. */
    public boolean isEmpty() {
        return name == null
                && description == null
                && url == null
                && licenses.isEmpty()
                && developers.isEmpty()
                && scm == null
                && repository == null
                && release == null
                && snapshotRepository == null
                && sign
                && keyFile == null
                && !skip
                && sbom
                && provenance
                && !hold;
    }

    /** This table with {@code skip} set to {@code value}. */
    public PomMetadata withSkip(boolean value) {
        return new PomMetadata(
                name,
                description,
                url,
                licenses,
                developers,
                scm,
                repository,
                release,
                snapshotRepository,
                sign,
                keyFile,
                value,
                sbom,
                provenance,
                hold);
    }

    /**
     * What Maven Central's validation requires and this table lacks, each as the key to add, in
     * table order; empty when the POM would pass. {@code description} is satisfied by the
     * project's own description.
     */
    public List<String> centralGaps(@Nullable String projectDescription) {
        List<String> gaps = new ArrayList<>();
        if (isBlank(description) && isBlank(projectDescription)) gaps.add("description");
        if (isBlank(url)) gaps.add("url");
        if (licenses.isEmpty()) gaps.add("licenses");
        if (developers.isEmpty()) gaps.add("developers");
        if (scm == null || isBlank(scm.url()) || isBlank(scm.connection()) || isBlank(scm.developerConnection())) {
            gaps.add("scm");
        }
        return List.copyOf(gaps);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
