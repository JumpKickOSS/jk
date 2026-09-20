// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Display-only shortening of a Maven group to the initial of each dot segment:
 * {@code org.apache.maven:maven-core} reads {@code o.a.m:maven-core}. Never used for lookups,
 * JSON or files, where the full coordinate stays.
 */
public final class GroupInitials {

    private GroupInitials() {}

    /** {@code o.a.m} for {@code org.apache.maven}; a single-segment or empty group is unchanged. */
    public static String group(String group) {
        if (group == null || group.indexOf('.') < 0) return group == null ? "" : group;
        StringBuilder sb = new StringBuilder();
        for (String segment : group.split("\\.", -1)) {
            if (segment.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append('.');
            sb.append(segment.charAt(0));
        }
        return sb.toString();
    }

    /**
     * {@code group:artifact} with the group shortened; the artifact and anything after it are
     * untouched. A value with no colon has no group and is returned as is.
     */
    public static String module(String groupArtifact) {
        if (groupArtifact == null) return "";
        int colon = groupArtifact.indexOf(':');
        if (colon <= 0) return groupArtifact;
        return group(groupArtifact.substring(0, colon)) + groupArtifact.substring(colon);
    }
}
