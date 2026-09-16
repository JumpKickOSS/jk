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
 * them if it cares. A workspace root's table applies to every member that declares none.
 */
public record PomMetadata(
        @Nullable String name,
        @Nullable String description,
        @Nullable String url,
        List<License> licenses,
        List<Developer> developers,
        @Nullable Scm scm) {

    public PomMetadata {
        licenses = licenses == null ? List.of() : List.copyOf(licenses);
        developers = developers == null ? List.of() : List.copyOf(developers);
    }

    /** No {@code [publish]} table: a POM with coordinate, description and dependencies only. */
    public static final PomMetadata EMPTY = new PomMetadata(null, null, null, List.of(), List.of(), null);

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
                && scm == null;
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
