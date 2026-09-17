// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

/** The shape of a coordinate a plugin schema key of type {@code coordinate} accepts. */
final class Coordinates {

    private Coordinates() {}

    /**
     * True for {@code group:artifact:version} with every segment non-blank; a fourth segment (the
     * classifier) and a {@code !type} suffix are allowed. A version may float ({@code ^2.1}).
     */
    static boolean wellFormed(String value) {
        String coordinate = value.strip();
        int bang = coordinate.indexOf('!');
        if (bang >= 0) {
            if (coordinate.substring(bang + 1).isBlank()) return false;
            coordinate = coordinate.substring(0, bang);
        }
        String[] segments = coordinate.split(":", -1);
        if (segments.length < 3 || segments.length > 4) return false;
        for (String segment : segments) {
            if (segment.isBlank()) return false;
        }
        return true;
    }
}
