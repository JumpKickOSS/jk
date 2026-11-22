// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One runtime classpath entry with its real coordinate-derived file name. {@code container}
 * is the exploded archive dir when the artifact's packaging is a container (an Android AAR:
 * {@code classes.jar}, {@code res/}, {@code AndroidManifest.xml}, {@code R.txt} inside) —
 * null for a plain jar. {@code jar} is the host-classpath entry (a container's
 * {@code classes.jar}); null when the container carries no classes.
 *
 * <p>{@code group}/{@code artifact}/{@code version} are the artifact's coordinate as the lock
 * records it, empty for a workspace sibling that has none. Read them; do not reconstruct a
 * coordinate from {@code jar}'s path — entries are served out of the content-addressed store,
 * so the path is a hash and carries no Maven layout.
 */
public record RuntimeEntry(
        String fileName,
        @Nullable Path jar,
        boolean snapshot,
        @Nullable Path container,
        String group,
        String artifact,
        String version) {

    public RuntimeEntry(String fileName, @Nullable Path jar, boolean snapshot, @Nullable Path container) {
        this(fileName, jar, snapshot, container, "", "", "");
    }

    /** {@code group:artifact:version}, or empty when this entry has no coordinate. */
    public String gav() {
        return group.isEmpty() || artifact.isEmpty() ? "" : group + ":" + artifact + ":" + version;
    }
}
