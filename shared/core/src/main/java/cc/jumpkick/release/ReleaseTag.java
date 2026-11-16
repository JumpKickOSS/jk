// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import org.jspecify.annotations.Nullable;

/**
 * The git tag a release carries: {@code [release] tag}, a template whose {@code {version}} is the
 * released version. Without one, {@code v<version>}; a workspace a group root lists carries its
 * artifact name in front, {@code <artifact>-v<version>}, since its siblings release versions of
 * their own in the same repository.
 */
public final class ReleaseTag {

    /** The template when {@code [release]} names none. */
    public static final String DEFAULT_TEMPLATE = "v{version}";

    private ReleaseTag() {}

    /**
     * The template when {@code [release]} names none, for a workspace whose artifact is {@code
     * artifact} under a group root, or {@code null} for a standalone one. The artifact name and
     * never the directory: a directory is renamed and means nothing outside its tree.
     */
    public static String defaultTemplate(@Nullable String groupedArtifact) {
        return groupedArtifact == null || groupedArtifact.isBlank()
                ? DEFAULT_TEMPLATE
                : groupedArtifact + "-" + DEFAULT_TEMPLATE;
    }

    /** {@code template} with {@code {version}} replaced by {@code version}. */
    public static String render(String template, String version) {
        return template.replace("{version}", version);
    }
}
